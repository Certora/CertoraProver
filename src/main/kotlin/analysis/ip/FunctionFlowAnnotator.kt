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
import analysis.dataflow.VariableLookupComputation
import analysis.ip.InternalFunctionHint.Companion.META_KEY
import analysis.numeric.*
import analysis.worklist.MonadicStatefulParallelWorklistIteration
import analysis.worklist.ParallelStepResult
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import bridge.ContractInstanceInSDC
import bridge.Method
import bridge.MethodInContract
import bridge.types.SolidityTypeDescription
import com.certora.collect.*
import compiler.JumpType
import datastructures.PersistentStack
import datastructures.persistentStackOf
import datastructures.stdcollections.*
import decompiler.BLOCK_SOURCE_INFO
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import evm.MASK_SIZE
import evm.MAX_EVM_UINT256
import kotlin.streams.*
import log.Logger
import log.LoggerTypes
import log.warnIfNull
import parallel.ParallelPool
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import report.TreeViewLocation
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.SolidityContract
import spec.cvlast.Visibility
import tac.MetaKey
import tac.NBId
import tac.TACBasicMeta
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors
import kotlin.jvm.optionals.getOrNull
import kotlin.math.absoluteValue

private val logger = Logger(LoggerTypes.FUNCTION_BUILDER)

fun <T> T?.warnIfNull(f: () -> String) = warnIfNull(logger, f)

@KSerializable
@Treapable
data class InternalFunctionFinderReport(
    val unresolvedFunctions: Set<QualifiedMethodSignature>,
): AmbiSerializable

val INTERNAL_FUNC_FINDER_INFO =
    MetaKey<InternalFunctionFinderReport>("internal.func.finder.info", erased = true)

object FunctionFlowAnnotator {
    val HANDLED_ANNOTATION = MetaKey.Nothing("internal.func.finder.processed")

    /**
     * Describes the type of jump out of a block
     */
    private sealed class Edge {
        /**
         * There is no explicit jump, we just fall through to our successor
         */
        object Fallthrough : Edge()

        /**
         * This is a conditional jump on [v]
         */
        data class Conditional(val v: TACSymbol.Var) : Edge()

        /**
         * We are jumping to a known location, not as part of a function return
         */
        object Immediate : Edge()

        /**
         * We are jumping out of a function, with [v] as the return location
         */
        data class Indirect(val v: TACSymbol.Var) : Edge()
    }

    private fun getMethodReferenceSignature(method: MethodInContract) =
        method.method.toMethodSignature(SolidityContract(method.declaringContract), Visibility.INTERNAL)

    private fun getMethodReferenceSignature(method: Method, declaringContract: SolidityContract) =
        method.toMethodSignature(declaringContract, Visibility.INTERNAL)


    /**
        Finds the injected function finder hints, which are assignments to fake memory locations that match
        `isInternalAnnotationConstant`. We replace these assignments with `InternalFunctionHint` annotations, which we
        process further later.
     */
    private fun instrumentInternalFunctionHints(c: CoreTACProgram) : CoreTACProgram {
        val constantAnalysis = MustBeConstantAnalysis(c.analysisCache.graph)

        val replacements = c.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd is TACCmd.Simple.AssigningCmd.ByteStore && cmd.base == TACKeyword.MEMORY.toVar()) {
                constantAnalysis.mustBeConstantAt(ptr, cmd.loc)
                    ?.takeIf { it.isInternalAnnotationConstant() }
                    ?.let { loc ->
                        ptr to InternalFunctionHint(
                            id = loc.shiftRight(16).and(internalAnnotationFlagMask).toInt(),
                            flag = loc.and(internalAnnotationFlagMask).toInt(),
                            sym = cmd.value
                        )
                    }
            } else {
                null
            }
        }.asSequence()

        val p = c.toPatchingProgram()
        replacements.forEach { (ptr, hint) ->
            p.replaceCommand(ptr, listOf(TACCmd.Simple.AnnotationCmd(META_KEY, hint)))
        }
        return p.toCodeNoTypeCheck(c)
    }


    fun doAnalysis(uninstrumented: CoreTACProgram, source: ContractInstanceInSDC) : CoreTACProgram {
        val c = instrumentInternalFunctionHints(uninstrumented)
        val g = c.analysisCache.graph
        val seed = g.blocks.parallelStream().map { it to it.commands.last() }.map { (it, lc) ->
            it.id to when (val cmd = lc.cmd) {
                is TACCmd.Simple.JumpCmd -> it.commands.takeIf { it.size > 1 }?.get(it.commands.lastIndex - 1)
                    ?.maybeAnnotation(JUMP_SYM)?.v?.let { st ->
                        classifyJump(st, it)?.let { e ->
                            if (e is Edge.Indirect && cmd.metaSrcInfo?.jumpType != JumpType.EXIT) {
                                Edge.Immediate
                            } else {
                                e
                            }
                        }.warnIfNull {
                            "Failed to classify jump at ${it.id}, this will happen if the jump sym is computed via a complex expression"
                        } ?: error("Failed instrumenting ${c.name}")
                    }
                    ?: Edge.Fallthrough
                is TACCmd.Simple.JumpiCmd -> {
                    // sanity check, is the constant on the stack here indeed the location we are jumping to if the condition is true
                    val trueDest = it.commands.takeIf { it.size > 1 }?.get(it.commands.lastIndex - 1)
                        ?.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                        it.cmd.rhs is TACExpr.Sym.Const
                    }?.wrapped?.enarrow<TACExpr.Sym.Const>()?.exp?.s?.value
                    if (trueDest != null) {
                        check(cmd.dst.origStartPc == trueDest.toInt()) {
                            "Jumping to ${cmd.dst.origStartPc} but location on the stack was $trueDest"
                        }
                    }
                    Edge.Conditional(cmd.cond as TACSymbol.Var)
                }
                else -> {
                    check(g.succ(it.id).size <= 1) {
                        "broken graph, too many successors: ${g.succ(it)} $lc"
                    }
                    Edge.Fallthrough
                }
            }
        }.collect(Collectors.toMap({ it.first }, { it.second }))
        val w = Worker(c.analysisCache.graph, seed, source)
        val toAnnotateAsHandled = mutableSetOf<CmdPointer>()
        val toPrefix = mutableMapOf<CmdPointer, MutableList<TACCmd.Simple>>()
        if(!w.success) {
            logger.warn {
                "Failed to analyze ${c.name}"
            }
            return c
        }

        val didNotFind = mutableSetOf<QualifiedMethodSignature>()
        /*
         XXX(jtoman): the previous iteration of function finders would have a list of functions that we DEFINITELY
         expected to find: exactly those that we generated the auto-finders for. These names were also exactly names that
          were passed along in internalFunctions.

          The new version just instruments everything, and passes along all such names via internalFunctions. Now,
          if a function is never called as internal function anywhere in the contract, then we won't see an instance of
          that function, but that's not necessarily an error. Thus, the old mechanism of *expecting* to find all
          functions listed in internalFunctions doesn't work, and in fact leads to a loooot of spurious errors. This
          (now pointless) didNotFind set is here as a reminder of this deficiency, and a hope that we can revisit
          this question later.
         */
        didNotFind.clear() // TODO(jtoman): how do we know we missed one?
        val unresolved = mutableSetOf<QualifiedMethodSignature>()
        val handled = mutableSetOf<NBId>()
        val edgesByDest = w.complete.groupBy {
            it.dest.node
        }
        edgesByDest.forEach { (callSrc, v_) ->
            if(callSrc in handled) {
                return@forEach
            }
            val retSymPre = v_.map {
                it.dest.exitSym
            }.uniqueOrNull()
            check(v_.isNotEmpty()) {
                "Grouping is broken, have $callSrc with no calls?"
            }
            val v = if(retSymPre == null) {
                /*
                Try some simple heuristic: if we see two exit syms, and one was pushed earlier in the stack
                *and* the corresponding exit site is dominated by the exit site for the exit symbol pushed *later*,
                assume that the exit site pushed for "later" is some compiler inserted cleanup continuation, and exclude
                it.
                 */
                val minStackHeight = v_.monadicMap {
                    it.dest.exitSym.stackHeight()
                }?.minOrNull() ?: return@forEach
                val exitSymEdges = v_.filterToSet {
                    it.dest.exitSym.stackHeight() == minStackHeight
                }.takeIf { it.isNotEmpty() } ?: return@forEach
                val exitPoint = if(exitSymEdges.size == 1) {
                    exitSymEdges.single().src.node
                } else {
                    // confluence point?
                    exitSymEdges.monadicMap {
                        g.succ(it.src.node).singleOrNull()
                    }?.uniqueOrNull() ?: return@forEach
                }
                if(!v_.all {
                        it in exitSymEdges || g.cache.domination.dominates(exitPoint, it.src.node)
                    }) {
                    return@forEach
                }
                val trueRetSym = exitSymEdges.map { it.dest.exitSym }.uniqueOrNull() ?: return@forEach
                v_.filter {
                    it.dest.exitSym == trueRetSym
                }
            } else {
                v_
            }

            val returnSym = v.map {
                it.dest.exitSym
            }.uniqueOrNull().warnIfNull {
                "Found multiple exit symbols in $v for call at $callSrc"
            } ?: return@forEach
            // check that all the sources have the same singleton successor (single return)
            val uniqueExit = v.monadicMap {
                g.succ(it.src.node).singleOrNull()
            }?.uniqueOrNull().warnIfNull {
                "Multiple exits points for return for call at $callSrc ($v)"
            } ?: return@forEach
            val dst = g.succ(callSrc).singleOrNull().warnIfNull {
                "Multiple successors for call node $callSrc, ignoring"
            } ?: return@forEach
            val callSiteSrc = g.elab(callSrc).commands.last().cmd.metaSrcInfo
            val calleeSrc = g.elab(uniqueExit).commands.first().cmd.metaSrcInfo
            val dstPc = dst.origStartPc
            val block = g.elab(dst)
            val exitPoints = v.mapToSet {
                it.src.node
            }
            val siblingCalls = w.complete.asSequence().filter {
                it.src.node in exitPoints
            }.map {
                it.dest.node
            }.filter { siblingCandidate ->
                w.complete.filter {
                    it.dest.node == siblingCandidate
                }.let {
                    it.mapToSet { it.src.node } == exitPoints && it.all {
                        g.succ(it.dest.node).singleOrNull() == dst
                    }
                }
            }.toSet()

            val returnAddressHeight = returnSym.meta.find(TACBasicMeta.STACK_HEIGHT)
                ?: error("Missing stack height information for $returnSym ($v)")

            /*
             * Compute the *actual* start of the function, i.e., where we expect to see the internal hint annotations.
             *
             * At present, this entails skipping over default-initialized memory allocations that are inserted by the compiler
             * for struct types returned.
             *
             * TODO(jtoman): double check that these initializers do not mutate return/stack information
             */
            var properStart = block
            while(properStart.commands.none {
                it.maybeAnnotation(META_KEY) != null
                } && properStart.id in edgesByDest) {
                val edge = edgesByDest[properStart.id]?.singleOrNull() ?: break
                val nxt = getInitializerInfoOrNull(
                    t = edge,
                    w = w,
                    g = g
                ) ?: break
                properStart = g.elab(nxt)
            }

            // Enhanced resolution for nested library calls
            val resolved = if(properStart.commands.takeUntil {
                it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.k == META_KEY
                }?.any {
                    it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                } == true) {
                val prefix = properStart.commands.takeUntil {
                    it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.k == META_KEY
                }!!.toList()
                val indOf = prefix.indexOfLast {
                    it.cmd is TACCmd.Simple.AssigningCmd.ByteStore
                }
                check(indOf >= 0) {
                    "lol what $indOf $prefix"
                }
                val initPrefix = prefix.subList(0, indOf + 1)
                with(g) {
                    if(!isInitializerPrefix(initPrefix)) {
                        // For nested library calls, try alternative resolution strategies
                        val alternativeResolution = tryAlternativeResolution(properStart, source)
                        alternativeResolution ?: ResolutionHints.None
                    } else {
                        collectHelperAnnotations(
                            block = properStart,
                            startPoint = indOf + 1,
                            stackStart = null
                        )
                    }
                }
            } else {
                with(g) {
                    val standardResolution = collectHelperAnnotations(
                        block = properStart
                    )
                    // If standard resolution fails, try alternative for nested calls
                    if (standardResolution is ResolutionHints.None || standardResolution is ResolutionHints.FailureFor) {
                        val alternativeResolution = tryAlternativeResolution(properStart, source)
                        alternativeResolution ?: standardResolution
                    } else {
                        standardResolution
                    }
                }
            }
            val stackHeightAtExit = uniqueExit.stkTop
            val numReturn = (returnAddressHeight - stackHeightAtExit) + 1
            val functionId = (resolved as? ResolutionSuccess)?.internalId?.let(
                source.internalFunctions::get
            )?.let { nmString ->
                getMethodReferenceSignature(nmString)
            }
            if (resolved is ResolutionHints.FailureFor) {
                source.internalFunctions[resolved.id]?.let {getMethodReferenceSignature(it)}
                    ?.let(unresolved::add)
            }
            val thisId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
            val res = if (resolved is ResolutionSuccess && functionId != null) {
                didNotFind.remove(functionId)

                val annotationLocation = g.elab(dst).commands.first().ptr

                fun recordParseFailure() {
                    unresolved.add(functionId)
                }

                /**
                 * Finds the set of symbols equivalent to `this` at the annotation site (annotation location).
                 * This handles the case where argument symbols have been swapped around on the stack between
                 * the "start" of the function and where the actual annotations have occurred.
                 */
                fun TACSymbol.relocateOrNull(): Set<TACSymbol>? = when(this) {
                    is TACSymbol.Const -> setOf(this)
                    is TACSymbol.Var -> {
                        g.cache.gvn.findCopiesAt(annotationLocation, resolved.startLocation to this).takeIf { it.isNotEmpty() } ?: run {
                            logger.warn {
                                "For $functionId argument symbol $this at ${resolved.startLocation}, could not find copy at function start $annotationLocation"
                            }
                            recordParseFailure()
                            null
                        }
                    }
                }

                fun Set<TACSymbol>.pickBest(expectedStackHeight: Int): TACSymbol {
                    return this.filter {
                        it.stackHeight() != null
                    }.takeIf { it.isNotEmpty() }?.let outer@{ stkHeightVars ->
                        stkHeightVars.singleOrNull {
                            it.stackHeight()!! == expectedStackHeight
                        }?.let { return@outer it }
                        val minDistance = stkHeightVars.minOf {
                            (it.stackHeight()!! - expectedStackHeight).absoluteValue
                        }
                        stkHeightVars.singleOrNull {
                            (it.stackHeight()!! - expectedStackHeight).absoluteValue == minDistance
                        }?.let {
                            return@outer it
                        }
                        return stkHeightVars.minBy {
                            it.stackHeight()!!
                        }
                    } ?: this.first()
                }
                if (resolved is ResolutionHints.EmbeddedInfo) {
                    val stackOffsetToArgPos = treapMapOf<Int, Int>().mutate { res ->
                        var stackOffset = 1
                        resolved.args.forEachIndexed { argOffset, arg ->
                            res[stackOffset] = argOffset
                            when (arg) {
                                is StackArg.ConstantValue,
                                is StackArg.Scalar, is StackArg.ScalarPlaceHolder,
                                is StackArg.CalldataPointer, StackArg.CalldataPointerPlaceHolder -> {
                                    stackOffset++
                                }

                                is StackArg.DecomposedArray, is StackArg.ArrayPlaceHolder -> {
                                    // two [InternalFuncArgs] are corresponding to [argOffset], of sort
                                    // [CALLDATA_ARRAY_LENGTH], [CALLDATA_ARRAY_ELEMS]
                                    res[stackOffset + 1] = argOffset
                                    stackOffset += 2
                                }
                            }
                        }
                    }

                    val resolvedArgs = mutableListOf<InternalFuncArg>()
                    var stackPassingStyle = true
                    var hasPlaceholder = false
                    var expectedHeight = returnAddressHeight - 1
                    var sumOffset = 0
                    resolved.args.forEachIndexed { argIndex, arg ->
                        when (arg) {
                            StackArg.ArrayPlaceHolder -> {
                                if (!stackPassingStyle) {
                                    logger.warn {
                                        "for $functionId argument #$argIndex, requested computation of calldata offsets, but stack passing " +
                                                "convention appears violated"
                                    }
                                    recordParseFailure()
                                    return@forEach
                                }
                                hasPlaceholder = true
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = TACSymbol.Var.stackVar(expectedHeight - 1),
                                        sort = InternalArgSort.CALLDATA_ARRAY_ELEMS,
                                        offset = ++sumOffset,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset"),
                                        location = null
                                    )
                                )
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = TACSymbol.Var.stackVar(expectedHeight),
                                        sort = InternalArgSort.CALLDATA_ARRAY_LENGTH,
                                        offset = ++sumOffset,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset"),
                                        location = null
                                    )
                                )
                                expectedHeight -= 2
                            }
                            StackArg.CalldataPointerPlaceHolder,
                            StackArg.ScalarPlaceHolder -> {
                                if (!stackPassingStyle) {
                                    logger.warn {
                                        "For $functionId argument #$argIndex, requested placeholder argument, but stack passing convention " +
                                                "appears violated"
                                    }
                                    recordParseFailure()
                                    return@forEach
                                }
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = TACSymbol.Var.stackVar(expectedHeight),
                                        sort = if(arg is StackArg.ScalarPlaceHolder) {
                                            InternalArgSort.SCALAR
                                        } else {
                                            check(arg is StackArg.CalldataPointerPlaceHolder)
                                            InternalArgSort.CALLDATA_POINTER
                                        },
                                        offset = ++sumOffset,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset"),
                                        location = null
                                    )
                                )
                                hasPlaceholder = true
                                expectedHeight--
                            }
                            is StackArg.DecomposedArray -> {
                                val relocLen = arg.lenSym.relocateOrNull() ?: return@forEach
                                val relocOffset = arg.offsetSym.relocateOrNull() ?: return@forEach
                                if (relocLen.none { it.stackHeight() == expectedHeight } || relocOffset.none { it.stackHeight() == expectedHeight - 1 }) {
                                    logger.debug {
                                        "For $functionId argument #$argIndex $arg, violated stack convention at height $expectedHeight"
                                    }
                                    stackPassingStyle = false
                                }
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = relocOffset.pickBest(expectedHeight),
                                        offset = ++sumOffset,
                                        sort = InternalArgSort.CALLDATA_ARRAY_ELEMS,
                                        location = null,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset")
                                    )
                                )
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = relocLen.first(),
                                        sort = InternalArgSort.CALLDATA_ARRAY_LENGTH,
                                        offset = ++sumOffset,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset"),
                                        location = null
                                    )
                                )
                                expectedHeight -= 2
                            }
                            is StackArg.Scalar,
                            is StackArg.CalldataPointer -> {
                                check(arg is ScalarOnStack)
                                val relocV = arg.v.relocateOrNull() ?: return@forEach
                                if (relocV.none { it.stackHeight() == expectedHeight }) {
                                    stackPassingStyle = false
                                }
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = relocV.pickBest(expectedHeight),
                                        offset = ++sumOffset,
                                        sort = when(arg) {
                                            is StackArg.CalldataPointer -> InternalArgSort.CALLDATA_POINTER
                                            is StackArg.Scalar -> InternalArgSort.SCALAR
                                        },
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset"),
                                        location = null
                                    )
                                )
                                expectedHeight--
                            }
                            is StackArg.ConstantValue -> {
                                resolvedArgs.add(
                                    InternalFuncArg(
                                        s = arg.v,
                                        offset = ++sumOffset,
                                        sort = InternalArgSort.SCALAR,
                                        location = null,
                                        logicalPosition = stackOffsetToArgPos[sumOffset] ?: error("No position for $sumOffset")
                                    )
                                )
                            }
                        }
                    }
                    if (!stackPassingStyle && hasPlaceholder) {
                        logger.warn {
                            "Found argument array placeholder for $functionId @ $dstPc but stack passing style was violated"
                        }
                        recordParseFailure()
                        return@forEach
                    }
                    InternalFuncStartAnnotation(
                        startPc = dstPc,
                        id = thisId,
                        stackOffsetToArgPos = stackOffsetToArgPos,
                        methodSignature = functionId,
                        args = resolvedArgs.toList(),
                        callSiteSrc = callSiteSrc,
                        calleeSrc = calleeSrc
                    )
                } else {
                    check(resolved is ResolutionHints.ModifierInfo)
                    var expectedHeight = returnAddressHeight - 1
                    val args = mutableListOf<InternalFuncArg>()
                    val stackOffsetToArgPos = treapMapOf<Int, Int>().mutate { res ->
                        var argPos = 0
                        resolved.typeLayout.forEachIndexed { stackOffsetMinusOne, typ ->
                            res[stackOffsetMinusOne + 1] = argPos
                            when (typ) {
                                InternalArgSort.CALLDATA_POINTER,
                                InternalArgSort.SCALAR -> {
                                    argPos++
                                }

                                InternalArgSort.CALLDATA_ARRAY_ELEMS -> {
                                    check(resolved.typeLayout.getOrNull(stackOffsetMinusOne + 1) == InternalArgSort.CALLDATA_ARRAY_LENGTH) {
                                        "Expected $typ at stack offset ${stackOffsetMinusOne + 1} to have ${
                                            InternalArgSort.CALLDATA_ARRAY_LENGTH
                                        } at the next stack offset, but found ${
                                            resolved.typeLayout.getOrNull(
                                                stackOffsetMinusOne + 1
                                            )
                                        } "
                                    }
                                    // Do not increment [argPos] (referring to the same [argPos] as the
                                    // InternalArgSort of sort[CALLDATA_ARRAY_ELEMS]) coming after
                                }

                                InternalArgSort.CALLDATA_ARRAY_LENGTH -> {
                                    argPos++
                                }
                            }
                        }
                    }
                    resolved.typeLayout.forEachIndexed { indexTypeLayout, typeLayout ->
                        val sym = TACSymbol.Var.stackVar(expectedHeight)
                        val arg = InternalFuncArg(
                            s = sym,
                            sort = typeLayout,
                            offset = indexTypeLayout + 1,
                            location = null,
                            logicalPosition = stackOffsetToArgPos[indexTypeLayout + 1] ?: error("Incorrect offset without an argument position for $functionId")
                        )
                        args.add(arg)
                        if (resolved.resolvedHint != null && indexTypeLayout == resolved.resolvedHint.first) {
                            if(resolved.resolvedHint.third.relocateOrNull()?.any { sym == it } != true || resolved.resolvedHint.second != typeLayout) {
                                logger.warn {
                                    "Stack layout mismatch for $functionId (at $callSrc), expected $sym to be argument $indexTypeLayout " +
                                            " but the hint says it is actually ${resolved.resolvedHint.third} for $functionId"
                                }
                                recordParseFailure()
                                return@forEach
                            }
                        }
                        expectedHeight--
                    }
                    InternalFuncStartAnnotation(
                        startPc = dstPc,
                        id = thisId,
                        stackOffsetToArgPos = stackOffsetToArgPos,
                        methodSignature = functionId,
                        args = args.toList(),
                        callSiteSrc = callSiteSrc,
                        calleeSrc = calleeSrc
                    )
                }
            } else {
                // jk, I guess we don't want these around anymore
                return@forEach
            }
            val toAnnot = (resolved as ResolutionSuccess).handledAnnotation
            if(!toAnnotateAsHandled.add(toAnnot)) {
                logger.warn {
                    "Double annotation for pointer $toAnnot"
                }
            }
            val entryAnnotation = run {
                handled.addAll(siblingCalls)
                g.elab(dst).commands.first().ptr
            }
            toPrefix.computeIfAbsent(entryAnnotation) {
                mutableListOf()
            }.add(TACCmd.Simple.AnnotationCmd(
                INTERNAL_FUNC_START,
                res
            ))
            val returnVals = (0 until numReturn).map {
                InternalFuncRet(
                    offset = it,
                    s = TACSymbol.Var.stackVar(returnAddressHeight - it),
                    location = null
                )
            }
            v.forEach {
                toPrefix.computeIfAbsent(g.elab(it.src.node).commands.last().ptr) {
                    mutableListOf()
                }.add(
                    TACCmd.Simple.AnnotationCmd(
                        INTERNAL_FUNC_EXIT,
                        InternalFuncExitAnnotation(
                            id = thisId,
                            rets = returnVals.toList(),
                            methodSignature = functionId
                        )
                    )
                )
            }
        }
        val p = c.toPatchingProgram()
        for((where, what) in toPrefix) {
            p.replace(where) { l ->
                what + l
            }
        }
        for(where in toAnnotateAsHandled) {
            p.replace(where) { l ->
                check(l.maybeAnnotation(META_KEY) != null) {
                    "Attempting to annotate a meta key as handled, but it's not a metakey??"
                }
                listOf(l.plusMeta(HANDLED_ANNOTATION))
            }
        }
        unresolved.addAll(didNotFind)
        p.replace(c.analysisCache.graph.roots.singleOrNull()?.ptr ?: return c) { it ->
            listOf(TACCmd.Simple.AnnotationCmd(
                INTERNAL_FUNC_FINDER_INFO,
                InternalFunctionFinderReport(
                    unresolvedFunctions = unresolved
                )
            ),
                it)
        }
        return p.toCodeNoTypeCheck(c)
    }

    private fun mustRevertWithoutReturn(g: TACCommandGraph, blockId: NBId) : Boolean {
        return object : VisitingWorklistIteration<NBId, Unit, Boolean>() {
            override fun process(it: NBId): StepResult<NBId, Unit, Boolean> {
                val block = g.elab(it)
                val lst = block.commands.last()
                if(lst.cmd is TACCmd.Simple.ReturnCmd) {
                    return this.halt(false)
                }
                if(lst.cmd is TACCmd.Simple.RevertCmd) {
                    return this.result(Unit)
                }
                return this.cont(g.succ(it))
            }

            override fun reduce(results: List<Unit>): Boolean {
                return results.isNotEmpty()
            }

        }.submit(listOf(blockId))
    }

    /**
     * Checks if the given function hint location represents a nested internal library call.
     * This happens when one library's internal function calls another library's internal function.
     *
     * @param g The TAC command graph
     * @param where The location of the function hint
     * @param match The expected method reference
     * @return true if this appears to be a nested library call
     */
    private fun checkIfNestedLibraryCall(g: TACCommandGraph, where: CmdPointer, match: MethodInContract): Boolean {
        // Check if we're already inside an internal function
        var foundInternalContext = false

        // Depth limit to prevent infinite loops
        val maxDepth = 50
        var depth = 0
        val visited = mutableSetOf<NBId>()
        val toVisit = mutableSetOf<NBId>()
        toVisit.add(where.block)

        while (toVisit.isNotEmpty() && depth < maxDepth) {
            val blockId = toVisit.first()
            toVisit.remove(blockId)

            if (blockId in visited) {
                continue
            }
            visited.add(blockId)

            val block = g.elab(blockId)

            // Check if this block has internal function start annotations
            block.commands.forEach { cmd ->
                if (cmd.maybeAnnotation(INTERNAL_FUNC_START) != null) {
                    foundInternalContext = true
                    return@forEach  // Use return@forEach instead of break
                }
            }

            if (foundInternalContext) {
                break
            }

            // Add predecessors to visit
            toVisit.addAll(g.pred(blockId))
            depth++
        }

        // Also check if the method signatures suggest this is a library call
        val contract = match.declaringContract.lowercase()

        // List of common patterns for library contracts
        val libraryPatterns = listOf(
            "helper", "lib", "library", "util", "utils", "tool", "tools"
        )

        // Check if contract name ends with common library suffixes
        val hasLibrarySuffix = listOf("Library", "Lib", "Helper", "Helpers", "Utils", "Util")
            .any { suffix ->
                val contractName = match.declaringContract
                contractName.length >= suffix.length &&
                contractName.substring(contractName.length - suffix.length) == suffix
            }

        // Check if this looks like a library method based on naming patterns
        val methodPatterns = listOf("SafeMath", "Address", "Strings", "ECDSA", "MerkleProof")
        val looksLikeLibrary = methodPatterns.any { pattern -> match.declaringContract == pattern }

        val methodName = match.method.name.lowercase()
        val hasLibraryMethodPattern = listOf("calculate", "compute", "encode", "decode", "hash", "verify", "validate", "convert")
            .any { pattern -> methodName.indexOf(pattern) >= 0 }

        // Score-based approach
        var libraryScore = 0

        if (libraryPatterns.any { pattern -> contract.indexOf(pattern) >= 0 }) {
            libraryScore += 2
        }
        if (hasLibrarySuffix) {
            libraryScore += 3
        }
        if (looksLikeLibrary) {
            libraryScore += 4
        }
        if (hasLibraryMethodPattern) {
            libraryScore += 1
        }

        // Consider it a library if we have internal context or high library score
        return foundInternalContext || libraryScore >= 2
    }

    /**
     * Try alternative resolution strategies for nested internal library calls.
     * This is used when standard resolution fails, particularly for cases where
     * one library calls another library's internal function.
     */
    context(TACCommandGraph)
    private fun tryAlternativeResolution(block: TACBlock, source: ContractInstanceInSDC): ResolutionHints? {
        // Look for internal function hints in a wider context
        val hints = mutableListOf<InternalFunctionHint>()

        // Collect all hints in the block
        block.commands.forEach { cmd ->
            cmd.maybeAnnotation(META_KEY)?.let { hint ->
                hints.add(hint)
            }
        }

        if (hints.isEmpty()) {
            return null
        }

        // Try to extract type layout from existing hints
        val layoutHints = hints.filter { it.flag == 2 || it.flag == 3 || it.flag == 4 || it.flag == 5 }
        if (layoutHints.isNotEmpty()) {
            // We found layout hints, try to use them
            return null // Let the standard resolution handle it
        }

        // If we can't find enough information, try to infer from the method signature
        val idHint = hints.firstOrNull { it.flag == 0 }
        if (idHint != null && idHint.sym is TACSymbol.Const) {
            val functionId = "0x${idHint.sym.value.toString(16)}"
            val method = source.internalFunctions[functionId]?.method

            if (method != null) {
                // Try to infer type layout from method signature
                val inferredLayout = inferTypeLayoutFromMethod(method)

                return ResolutionHints.ModifierInfo(
                    internalId = functionId,
                    typeLayout = inferredLayout,
                    resolvedHint = null,
                    handledAnnotation = CmdPointer(block.id, 0),
                    startLocation = CmdPointer(block.id, 0)
                )
            }
        }

        return null
    }

    /**
     * Try to infer type layout from method signature.
     */
    private fun inferTypeLayoutFromMethod(method: Method): List<InternalArgSort> {
        val layout = mutableListOf<InternalArgSort>()

        method.fullArgs.forEach { arg ->
            when {
                // Arrays are represented as two arguments: offset and length
                arg.typeDesc is SolidityTypeDescription.Array -> {
                    layout.add(InternalArgSort.CALLDATA_ARRAY_ELEMS)
                    layout.add(InternalArgSort.CALLDATA_ARRAY_LENGTH)
                }
                // Dynamic bytes and string are also arrays
                arg.typeDesc is SolidityTypeDescription.PackedBytes -> {
                    layout.add(InternalArgSort.CALLDATA_ARRAY_ELEMS)
                    layout.add(InternalArgSort.CALLDATA_ARRAY_LENGTH)
                }
                arg.typeDesc is SolidityTypeDescription.StringType -> {
                    layout.add(InternalArgSort.CALLDATA_ARRAY_ELEMS)
                    layout.add(InternalArgSort.CALLDATA_ARRAY_LENGTH)
                }
                // Structs might be pointers
                arg.typeDesc is SolidityTypeDescription.UserDefined.Struct -> {
                    layout.add(InternalArgSort.CALLDATA_POINTER)
                }
                // Everything else is scalar
                else -> {
                    layout.add(InternalArgSort.SCALAR)
                }
            }
        }

        return layout
    }

    private data class WorkTuple(
        val node: NBId,
        val exitSym: TACSymbol.Var,
        val trackSym: TACSymbol.Var?
    )

    private data class WorkEdge(val src: WorkTuple, val dest: WorkTuple) {
        fun to(nxt: NBId, exitSym: TACSymbol.Var, toTrack: TACSymbol.Var?): WorkEdge {
            return this.copy(
                dest = WorkTuple(
                    node = nxt,
                    exitSym = exitSym,
                    trackSym = toTrack
                )
            )
        }
    }

    /**
     * Uses a simple, IFDS style tabulation algorithm to track source -> destination flows.
     *
     * A work edge is an edge of work tuples: each tuple has a node, a dataflow symbol, and a return symbol.
     *
     * The source tuple [WorkTuple] of a [WorkEdge] indicates what triggered a specific flow. For this source,
     * [WorkTuple.node] is always the exit node of a function call, and [WorkTuple.exitSym] is the symbol
     * that was used to jump out of said function call. The (nullable) [WorkTuple.trackSym] is the symbol
     * that is being tracked through the function call.
     *
     * The [WorkEdge.dest] of a work edge indicates the "current" location of the analysis that started at
     * [WorkEdge.src]. In other words, when starting at [WorkEdge.src] with a specific [WorkTuple.exitSym],
     * after tracing backwards from [WorkTuple.node] to reach the (exit point of) node at [WorkEdge.dest], we *must* have that
     * the return symbol is stored in location given by the [WorkEdge.dest]'s [WorkTuple.exitSym] field. A similar
     * relationship holds for the value of [WorkTuple.trackSym], starting from the source node, when the
     * analysis reaches (the exit of) [WorkEdge.dest]'s [WorkTuple.node], the symbol that began in [WorkEdge.src]'s [WorkTuple.trackSym]
     * is now stored in the [WorkEdge.dest]'s [WorkTuple.exitSym] field.
     *
     * When tracing back through a function, the analysis may encounter a function return, i.e., the flow will traverse
     * a return jump backwards. In this case, the current analysis of the outer function is suspended, and (up to) two new
     * flows are started: one to trace the path of the current value of the [WorkTuple.exitSym] through the called function, and one to trace
     * the current value of the [WorkTuple.trackSym] through the called function. These "child" flows run
     * until they reach the jump out of the called function, at which point the computed "child" flows are composed with the
     * source flow in the caller, and the outer analysis continues.
     *
     * Let (N, T, E) -> (N', T', E') be a work edge that reaches a return from a function call at N2 with a return symbol
     * R. Then we spawn:
     *
     * (N2, E', R) -> (N2, E', R) and
     * (N2, T', R) -> (N2, T', R)
     *
     * And record that (N, T, E) -> (N', T', E') is awaiting the completion of the analyses starting at (N2, E', R) and (N2, T', R).
     * The forward version of this relationship is recorded in [pending]; the backwards version, which maps a start tuple
     * to the work edges that depend on it, is [incoming].
     *
     * When the analysis of (N2, E', R) completes (i.e., it reaches the call corresponding to the return), the output tuple
     * is recorded in [summary], and the incoming edges are checked for the source tuple is consulted. This gives a
     * collection of pending [WorkEdge] that were waiting for the analysis to complete. If all of the spawned analyses that
     * the original workedge have completed, the results recorded in the summary are used to extend the pending [WorkEdge]
     * and the outer analysis resumes.
     *
     * Returning to our example, suppose the two spawned analyses complete with (N2, E', R) -> (N3, E'', R') and
     * (N2, T', R) -> (N3, T'', R') respectively. N3 here is the location where the analysis should resume,
     * and T'' and E'' are the new values to be tracked. Thus, the analysis adds (N, T, E) -> (N3, T'', E'') to
     * the work list, and resumes.
     *
     * Once the worklist is drained, [complete] holds the set of [WorkEdge] that describe "top-level" function calls, i.e.,
     * for each function return, what are the corresponding call locations (the return location is represented in the [WorkEdge.src]
     * field, and the call locations in the [WorkEdge.dest], this reversal is because this is a backwards analysis).
     */
    private class Worker(private val g: TACCommandGraph, seed: Map<NBId, Edge>, source: ContractInstanceInSDC) {
        val knownFunctionStarts = source.internalFunctionStarts.toSet()
        val visited = mutableSetOf<WorkEdge>()
        val incoming = mutableMapOf<WorkTuple, MutableCollection<WorkEdge>>()
        val summary = mutableMapOf<WorkTuple, MutableSet<WorkTuple>>()
        val pending = mutableMapOf<WorkEdge, MutableCollection<WorkTuple>>()
        val complete by lazy {
            summary.flatMap { (src, dst) ->
                if(src.trackSym != null) {
                    return@flatMap listOf()
                }
                dst.map {
                    WorkEdge(
                        src = src,
                        dest = it
                    )
                }
            }
        }
        val success : Boolean
        init {
            // Accessing this early prevents some problematic lazy initialization recursion; see Lazy.kt
            val blockVars = g.cache[VariableLookupComputation]

            val workItems = mutableListOf<WorkEdge>()
            seed.mapNotNull { (nb, e) ->
                (e as? Edge.Indirect)?.let {
                    WorkEdge(
                        WorkTuple(nb, exitSym = it.v, trackSym = null),
                        WorkTuple(nb, exitSym = it.v, trackSym = null)
                    )
                }
            }.forEach { k ->
                visited.add(k)
                workItems.add(k)
            }
            success = (object : MonadicStatefulParallelWorklistIteration<WorkEdge, (MutableCollection<WorkEdge>, MutableCollection<Boolean>) -> Unit, Boolean, Boolean>(
              inheritPool = (Thread.currentThread() as? ParallelPool.ParallelPoolWorkerThread)?.parallelPool
            ) {
                override fun commit(
                    c: (MutableCollection<WorkEdge>, MutableCollection<Boolean>) -> Unit,
                    nxt: MutableCollection<WorkEdge>,
                    res: MutableCollection<Boolean>
                ) {
                    c(nxt, res)
                }

                override fun reduce(results: List<Boolean>) : Boolean {
                    return results.none {
                        !it
                    }
                }

                fun flowComplete(res: WorkEdge, nxt: MutableCollection<WorkEdge>) : Boolean {
                    val added = summary.computeIfAbsent(res.src) { mutableSetOf() }.add(res.dest)
                    if(!added) {
                        return true
                    }
                    val spawning = incoming[res.src] ?: return true
                    for(p in spawning) {
                        val pend = pending[p] ?: return false
                        if(pend.all { summary[it]?.isNotEmpty() ?: false }) {
                            val calleeStart = res.src.node
                            val newEdge = composeSummary(p, calleeStart) ?: return false
                            queueNext(nxt, newEdge)
                        }
                    }
                    return true
                }

                fun composeSummary(p: WorkEdge, calleeStart: NBId) : Collection<WorkEdge>? {
                    val calleeReturnSym = seed[calleeStart]?.let { it as? Edge.Indirect }?.v ?: throw IllegalArgumentException("Jump into $calleeStart wasn't actually a call")
                    val exitSymLoc = WorkTuple(
                        exitSym = calleeReturnSym,
                        trackSym = p.dest.exitSym,
                        node = calleeStart
                    ).let(summary::get) ?: return null
                    return if(p.dest.trackSym != null) {
                        exitSymLoc.flatMap { nestedExit ->
                            WorkTuple(exitSym = calleeReturnSym, node = calleeStart, trackSym = p.dest.trackSym).let(summary::get)!!.filter {
                                it.node == nestedExit.node && it.exitSym == nestedExit.exitSym
                            }.map {
                                p.copy(dest = WorkTuple(
                                    exitSym = nestedExit.trackSym!!,
                                    trackSym = it.trackSym!!,
                                    node = nestedExit.node
                                ))
                            }
                        }
                    } else {
                        exitSymLoc.map {
                            p.copy(dest = WorkTuple(
                                node = it.node,
                                trackSym = null,
                                exitSym = it.trackSym!!
                            ))
                        }
                    }
                }

                /*
                 * Returns true if the call has not yet been analyzed
                 */
                fun pushCall(src: WorkEdge, v: TACSymbol.Var, where: NBId, nxt: MutableCollection<WorkEdge>) : Boolean {
                    val exitSym = seed[where]?.let { it as? Edge.Indirect }?.v ?: throw IllegalArgumentException("Can't jump into non-call @ $where")
                    val startTuple = WorkTuple(
                        node = where,
                        exitSym = exitSym,
                        trackSym = v
                    )
                    incoming.computeIfAbsent(startTuple) { mutableSetOf() }.add(src)
                    pending.computeIfAbsent(src) { mutableSetOf() }.add(startTuple)
                    /*
                      We spawn exactly once
                     */
                    if(startTuple in summary) {
                        return false
                    }
                    queueNext(
                        nxt, WorkEdge(
                            src = startTuple,
                            dest = startTuple
                        )
                    )
                    return true
                }

                override fun process(
                    it: WorkEdge
                ): ParallelStepResult<(MutableCollection<WorkEdge>, MutableCollection<Boolean>) -> Unit, Boolean, Boolean> {
                    var curr = it.dest.exitSym
                    var toTrack = it.dest.trackSym
                    val block = g.elab(it.dest.node)
                    if(curr in blockVars[it.dest.node].orEmpty() || toTrack?.let(blockVars[it.dest.node].orEmpty()::contains) == true) {
                        for (l in block.commands.reversed()) {
                            if (l.cmd !is TACCmd.Simple.AssigningCmd || (l.cmd.lhs != curr && l.cmd.lhs != toTrack)) {
                                continue
                            }
                            if (l.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || l.cmd.rhs !is TACExpr.Sym) {
                                logger.warn {
                                    "Return symbol $it reached a non-constant definition site: $l. Failing analysis"
                                }
                                return this.halt(false)
                            }
                            if(l.cmd.lhs == toTrack) {
                                if(l.cmd.rhs !is TACExpr.Sym.Var) {
                                    return this.halt(false)
                                }
                                toTrack = l.cmd.rhs.s
                                continue
                            }
                            if (l.cmd.rhs !is TACExpr.Sym.Var) {
                                check(l.cmd.rhs is TACExpr.Sym.Const) {
                                    "${l.cmd.rhs} is a sym, but isn't a var or const?"
                                }
                                return this.cont { nxt, res ->
                                    if(!flowComplete(
                                        it, nxt
                                    )) {
                                        res.add(false)
                                    }
                                }
                            }
                            curr = l.cmd.rhs.s
                        }
                    }
                    return this.cont { nxt, res ->
                        val pred = g.pred(it.dest.node)
                        if(pred.isEmpty()) {
                            logger.warn { "$it reached the root of the contract without hitting a push, failing" }
                            res.add(false)
                        }
                        for (p in pred) {
                            val newItem = it.to(
                                nxt = p, exitSym = curr, toTrack = toTrack
                            )
                            val classification = seed[p]!!
                            val prev = g.elab(p)
                            if(classification is Edge.Indirect && prev.commands.last().maybeNarrow<TACCmd.Simple.JumpCmd>()?.cmd?.metaSrcInfo?.jumpType == JumpType.EXIT) {
                                val spawnedExit = pushCall(
                                    newItem,
                                    where = p,
                                    nxt = nxt,
                                    v = newItem.dest.exitSym
                                )
                                val spawnedTrack = newItem.dest.trackSym?.let {
                                    pushCall(
                                        newItem,
                                        where = p,
                                        nxt = nxt,
                                        v = it
                                    )
                                } ?: false
                                if(!spawnedTrack && !spawnedExit) {
                                    val composed = composeSummary(newItem, p)
                                    if(composed == null) {
                                        res.add(false)
                                        return@cont
                                    }
                                    queueNext(nxt, composed)
                                }
                            } else if(classification is Edge.Immediate && (prev.commands.last().maybeNarrow<TACCmd.Simple.JumpCmd>()?.cmd?.metaSrcInfo?.jumpType == JumpType.ENTER ||
                                it.dest.node.origStartPc in knownFunctionStarts)) {
                                if(!flowComplete(newItem, nxt)) {
                                    res.add(false)
                                }
                            } else {
                                queueNext(nxt, newItem)
                            }
                        }
                    }
                }
            }).submit(workItems)

        }

        private fun queueNext(nxt: MutableCollection<WorkEdge>, composed: Collection<WorkEdge>) {
            composed.forEach {
                queueNext(nxt, it)
            }
        }

        private fun queueNext(
            nxt: MutableCollection<WorkEdge>,
            composed: WorkEdge
        ) {
            if(composed in visited) {
                return
            }
            visited.add(composed)
            nxt.add(composed)
        }
    }

    fun validateIds(c: CoreTACProgram, source: ContractInstanceInSDC): CoreTACProgram {
        // go over hints, the first internalfunc start annotation should match the id
        val unknownFunctionIds = mutableSetOf<Pair<CmdPointer, String?>>()
        val functionIdsWithoutAMatch = mutableSetOf<Pair<CmdPointer, String>>()
        val functionIdsWithWrongMatch = mutableSetOf<Pair<CmdPointer, String>>()
        val g = c.analysisCache.graph
        g.commands.mapNotNull { it `to?` it.maybeAnnotation(InternalFunctionHint.META_KEY)?.takeIf { it.flag == 0 } }
            .forEach { (lc, hint) ->
                val where = lc.ptr
                val functionId = hint.sym
                val asHexStr = (functionId as? TACSymbol.Const)?.value?.toString(16)?.let { "0x${it}" }
                if (asHexStr == null || asHexStr !in source.internalFunctions) {
                    unknownFunctionIds.add(where to asHexStr)
                    return@forEach
                }
                val match = source.internalFunctions[asHexStr]!!
                val matchInBlock = g.iterateRevBlock(where).firstNotNullOfOrNull { it.maybeAnnotation(INTERNAL_FUNC_START)?.methodSignature }

                // For nested internal library calls, we need to check more broadly
                val matchInNestedContext = if (matchInBlock == null && HANDLED_ANNOTATION !in lc.cmd.meta) {
                    // Try to find the annotation in the predecessor blocks as well
                    // This handles cases where the compiler may have split the function across blocks
                    val blockId = where.block
                    val predecessors = g.pred(blockId)

                    predecessors.firstNotNullOfOrNull { predId ->
                        g.elab(predId).commands.reversed().firstNotNullOfOrNull {
                            it.maybeAnnotation(INTERNAL_FUNC_START)?.methodSignature
                        }
                    }
                } else {
                    matchInBlock
                }

                if (matchInNestedContext == null && HANDLED_ANNOTATION !in lc.cmd.meta) {
                    if(mustRevertWithoutReturn(c.analysisCache.graph, lc.ptr.block)) {
                        return@forEach
                    }
                    // For nested library calls, we need to be more lenient
                    // Check if this is a nested library call by examining the context
                    val isNestedLibraryCall = checkIfNestedLibraryCall(g, where, match)
                    if (!isNestedLibraryCall) {
                        functionIdsWithoutAMatch.add(where to asHexStr)
                    } else {
                        // Log this as a warning instead of an error for nested library calls
                        logger.warn { "${g.name}: Nested internal library call detected for ${getMethodReferenceSignature(match)} @ $where" }
                    }
                    return@forEach
                }
                if (getMethodReferenceSignature(match) != matchInNestedContext && matchInNestedContext != null) {
                    functionIdsWithWrongMatch.add(where to asHexStr)
                }
            }

        val missed = functionIdsWithoutAMatch.mapToSet { it.second }.mapNotNullToTreapSet {
            source.internalFunctions[it]?.let { m ->
                m.method.toMethodSignature(SolidityContract(m.declaringContract), Visibility.INTERNAL)
            }
        }

        // print all bad matches
        fun printInternalFuncMiss(message: String, where: CmdPointer) {
            val funcStart = getContainingInternalFuncStart(where, g)
            printInternalFuncMiss(
                message = message,
                callerContractName = c.name,
                hint = funcStart?.descWithContentAndLocation(),
                range = funcStart?.callSiteSrc?.getSourceDetails()
            )
        }

        fun Set<Pair<CmdPointer, String?>>.distinctByFuncStart() = this.distinctBy { getContainingInternalFuncStart(it.first, g) to it.second }

        // for each set, we don't need multiple entries if the hint will be empty.
        unknownFunctionIds.distinctByFuncStart().forEach { (where, functionIdAsHexStr) ->
            logger.error { "${g.name}: Could not find any function in source contract ${source.name} with id $functionIdAsHexStr @ $where (source: ${getSourceStringOrInternalFuncForPtr(g.elab(where))})" }
        }

        functionIdsWithoutAMatch.distinctByFuncStart().forEach { (where, functionIdAsHexStr) ->
            val internalFunc = source.internalFunctions[functionIdAsHexStr] ?: `impossible!`
            printInternalFuncMiss("Could not detect the internal function ${getMethodReferenceSignature(internalFunc)}", where)
            logger.error { "${g.name}: Could not detect the internal function ${getMethodReferenceSignature(internalFunc)} @ $where" }
        }

        functionIdsWithWrongMatch.distinctByFuncStart().forEach { (where, functionIdAsHexStr) ->
            val internalFunc = source.internalFunctions[functionIdAsHexStr] ?: `impossible!`
            // the alert may be confusing so starting with logs that will be monitored
            // printInternalFuncMiss("Detected an internal function but it's not matching, we expected ${getMethodReferenceSignature(internalFunc)}", where)

            // Check if this is a nested library call to adjust the severity
            val isNestedCall = checkIfNestedLibraryCall(g, where, internalFunc)
            if (isNestedCall) {
                logger.warn { "${g.name}: Detected a nested internal library function call that's not matching, we expected ${getMethodReferenceSignature(internalFunc)} @ $where (source: ${getSourceStringOrInternalFuncForPtr(g.elab(where))})" }
            } else {
                logger.error { "${g.name}: Detected an internal function but it's not matching, we expected ${getMethodReferenceSignature(internalFunc)} @ $where (source: ${getSourceStringOrInternalFuncForPtr(g.elab(where))})" }
            }
        }

        val existing = c.parallelLtacStream().filter {
            it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.maybeAnnotation(INTERNAL_FUNC_FINDER_INFO) != null
        }.findFirst().getOrNull()

        if(existing != null) {
            val report = existing.maybeAnnotation(INTERNAL_FUNC_FINDER_INFO)!!
            return c.toPatchingProgram().let { it ->
                it.replaceCommand(existing.ptr, listOf(TACCmd.Simple.AnnotationCmd(
                    INTERNAL_FUNC_FINDER_INFO,
                    report.copy(unresolvedFunctions = report.unresolvedFunctions + missed)
                )))
                it.toCodeNoTypeCheck(c)
            }
        } else {
            return c.prependToBlock0(
                listOf(
                    TACCmd.Simple.AnnotationCmd(
                        INTERNAL_FUNC_FINDER_INFO,
                        InternalFunctionFinderReport(
                            unresolvedFunctions = missed
                        )
                    )
                )
            )
        }
    }

    private fun printInternalFuncMiss(message: String, callerContractName: String?, hint: String?, range: TreeViewLocation?) {
        CVTAlertReporter.reportAlert(
            CVTAlertType.INTERNAL_FUNCTION_ANALYSIS,
            CVTAlertSeverity.WARNING,
            range,
            callerContractName?.let { "Failed to locate an internal function called from ${callerContractName}: $message" }
                ?: "Failed to locate an internal function: $message",
            hint.takeIf { range == null },
        )
    }

    fun reportUnusmmarizableInternalFunctions(source: ContractInstanceInSDC) {
        // addendum. Let's add a global warning for internal functions using external function pointers.
        // the Python is warning about them, but it's easy to miss from console output
        // also, since there were no instrumented autofinders, we won't find them here
        source.allMethods.filter { func ->
            (func.visibility == Method.MethodVisibility.internal || func.visibility == Method.MethodVisibility.public)
                && func.fullArgs.any { arg -> arg.typeDesc is SolidityTypeDescription.Function }
        }.forEach { internalFuncWithExternalFuncPtr ->
            printInternalFuncMiss("The internal function ${getMethodReferenceSignature(internalFuncWithExternalFuncPtr, SolidityContract(source.name))} " +
                "contains external function pointer arguments", null, null, internalFuncWithExternalFuncPtr.sourceSegment())
        }
    }

    private fun classifyJump(start: TACSymbol.Var, lc: TACBlock): Edge? {
        var curr = start
        lc.commands.reversed().forEach {
            if(it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs == curr) {
                // simple assignment first
                if (it.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    return null
                }
                when (it.cmd.rhs) {
                    is TACExpr.Sym.Var -> {
                        if(it.cmd.rhs is TACExpr.Sym.Const) {
                            return Edge.Immediate
                        }
                        curr = it.cmd.rhs.s
                    }
                    is TACExpr.BinOp.BWAnd -> {
                        // tailored for function pointers normalization
                        val rhs = it.cmd.rhs
                        val s = if (rhs.o1.evalAsConst() == MASK_SIZE(32)) {
                            rhs.o2
                        } else if(rhs.o2.evalAsConst() == MASK_SIZE(32)) {
                            rhs.o1
                        } else {
                            return null
                        }
                        if (s is TACExpr.Sym.Const) {
                            return Edge.Immediate
                        }
                        curr = (s as? TACExpr.Sym.Var)?.s ?: return null
                    }
                    else -> return null
                }
            }
        }
        return Edge.Indirect(start)
    }

    private fun TACSymbol.stackHeight() : Int? = (this as? TACSymbol.Var)?.meta?.find(TACBasicMeta.STACK_HEIGHT)

    private sealed interface SingleSlotArg

    private sealed interface ScalarOnStack : SingleSlotArg {
        val v: TACSymbol.Var
    }

    private sealed class StackArg {
        data class Scalar(override val v: TACSymbol.Var) : StackArg(), ScalarOnStack
        data class CalldataPointer(override val v: TACSymbol.Var) : StackArg(), ScalarOnStack
        data class ConstantValue(val v: TACSymbol.Const) : StackArg(), SingleSlotArg
        data class DecomposedArray(val lenSym: TACSymbol.Var, val offsetSym: TACSymbol) : StackArg()
        object ArrayPlaceHolder : StackArg()
        object ScalarPlaceHolder : StackArg()
        object CalldataPointerPlaceHolder : StackArg()
    }

    sealed interface ResolutionSuccess {
        val numArgs: Int
        val internalId: String
        val handledAnnotation: CmdPointer
        val startLocation: CmdPointer
    }

    private sealed class ResolutionHints {
        data class EmbeddedInfo(
            override val internalId: String,
            val args: List<StackArg>,
            override val handledAnnotation: CmdPointer,
            override val startLocation: CmdPointer
        ) : ResolutionHints(), ResolutionSuccess {
            override val numArgs: Int
                get() = args.map {
                    when(it) {
                        StackArg.ArrayPlaceHolder,
                        is StackArg.DecomposedArray -> 2
                        is StackArg.ConstantValue,
                        is StackArg.Scalar,
                        is StackArg.CalldataPointer,
                        is StackArg.CalldataPointerPlaceHolder,
                        StackArg.ScalarPlaceHolder -> 1
                    }
                }.sum()

        }
        data class ModifierInfo(
            override val internalId: String,
            val typeLayout : List<InternalArgSort>,
            val resolvedHint: Triple<Int, InternalArgSort, TACSymbol.Var>?,
            override val handledAnnotation: CmdPointer,
            override val startLocation: CmdPointer
        ) : ResolutionHints(), ResolutionSuccess {
            override val numArgs: Int
                get() = typeLayout.size
        }
        object None : ResolutionHints()
        data class FailureFor(val msg: String, val id: String) : ResolutionHints()
    }

    private data class InitObject(val sz: BigInteger?, val initializedSlots: Set<BigInteger>) {
        fun fullyInitialized() = sz != null && ((sz / EVM_WORD_SIZE).toIntOrNull()?.let { numFields ->
            (0 until numFields).all {
                (it * EVM_WORD_SIZE_INT).toBigInteger() in initializedSlots
            }
        }.also {
            if(it == null) {
                logger.debug { "Implausibly large block size $sz" }
            }
        } ?: false)
    }

    @JvmInline
    private value class AbstractHeap(val backing: Map<CmdPointer, InitObject>)

    private sealed interface AbstractValue {
        data class InitializingPointer(
            val read: CmdPointer,
            val offset: BigInteger
        ) : AbstractValue
        data class FinalizedPointer(val read: CmdPointer) : AbstractValue

        data class Const(val wrapped: BigInteger?) : AbstractValue, UIntApprox<Const>, WithUIntApproximation<Const> {
            companion object {
                val nondet = Const(null)
            }

            override fun widen(next: Const): Const {
                return join(next)
            }

            override fun join(other: Const): Const {
                return if(wrapped == other.wrapped) {
                    this
                } else {
                    Const(null)
                }
            }

            override fun getUpperBound(): BigInteger {
                return wrapped ?: MAX_EVM_UINT256
            }

            override fun getLowerBound(): BigInteger {
                return wrapped ?: BigInteger.ZERO
            }

            private val nondetOverflow : Pair<Const, Boolean> get() = nondet to true

            private fun BigInteger.withinBounds() = BigInteger.ZERO <= this && this <= MAX_EVM_UINT256

            private fun BigInteger.withOverflow() = if(this.withinBounds()) {
                Const(this) to false
            } else {
                null
            }

            override fun mult(other: Const): Pair<Const, Boolean> {
                return other.wrapped?.multiply(this.wrapped ?: return nondetOverflow)?.withOverflow() ?: nondetOverflow
            }

            override fun add(other: Const): Pair<Const, Boolean> {
                return other.wrapped?.add(this.wrapped ?: return nondetOverflow)?.withOverflow() ?: nondetOverflow
            }

            override fun sub(other: Const): Pair<Const, Boolean> {
                return this.wrapped?.subtract(other.wrapped ?: return nondetOverflow)?.withOverflow() ?: nondetOverflow
            }

            override fun shiftLeft(lb: BigInteger, ub: BigInteger): Const {
                return nondet
            }

            override fun mayOverlap(other: Const): Boolean {
                return this.c == other.c
            }

            override val c: BigInteger
                get() = wrapped!!

            override val isConstant: Boolean
                get() = wrapped != null

            override fun shiftRight(lb: BigInteger, ub: BigInteger): Const {
                return nondet
            }

            override val x: Const
                get() = this
        }
    }

    private data class AbstractState(val heap: AbstractHeap, val stack: Map<TACSymbol.Var, AbstractValue>)

    private val semantics = object : IntValueSemantics<AbstractValue.Const, AbstractValue.Const, AbstractState, Any>() {
        override fun lift(lb: BigInteger, ub: BigInteger): AbstractValue.Const {
            return if(lb == ub) {
                AbstractValue.Const(lb)
            } else {
                nondet
            }
        }

        override fun lift(iVal: AbstractValue.Const): AbstractValue.Const {
            return iVal
        }

        override val nondet: AbstractValue.Const
            get() = AbstractValue.Const.nondet
    }

    private val caseSwitch = object : ValueSemanticsExpressionSwitch<AbstractState, Any, AbstractValue.Const>(semantics, AbstractValue.Const(null)) {
        override fun interp(o: TACSymbol, s: AbstractState): AbstractValue.Const {
            return when(o) {
                is TACSymbol.Var -> s.stack[o]?.let { it as? AbstractValue.Const } ?: AbstractValue.Const.nondet
                is TACSymbol.Const -> AbstractValue.Const(o.value)
            }
        }
    }

    context(TACCommandGraph)
    private fun step(s: AbstractState, lc: LTACCmd) : Either<AbstractState, String> {
        fun err(s: () -> String) = "$lc: ${s()}".toRight()
        fun interp(o: TACSymbol) = when (o) {
            is TACSymbol.Const -> AbstractValue.Const(o.value)
            is TACSymbol.Var -> s.stack[o] ?: AbstractValue.Const.nondet
        }
        fun handleAllocation(l: LTACCmdView<TACCmd.Simple.AssigningCmd>) : Either<AbstractState, String> {
            if(s.heap.backing.any { (_, io) ->
                    io.sz == null
                }) {
                return err {
                    "Already have extant unfinished allocation"
                }
            } else if(lc.ptr in s.heap.backing) {
                return err {
                    "Somehow already have allocation here in the heap ${s.heap}"
                }
            }
            return AbstractState(
                stack = s.stack + (l.cmd.lhs to AbstractValue.InitializingPointer(
                    read = lc.ptr,
                    offset = BigInteger.ZERO
                )),
                heap = AbstractHeap(s.heap.backing + (lc.ptr to InitObject(
                    sz = null,
                    initializedSlots = setOf()
                )))
            ).toLeft()
        }
        fun handleAllocClose(provider: () -> Either<TACSymbol.Var, String>) : Either<AbstractState, String> {
            val v = provider()
            if(v !is Either.Left) {
                return v.uncheckedAs()
            }
            val newPointer = (s.stack[v.d] as? AbstractValue.InitializingPointer) ?: return err {
                "RHS for free pointer is not an initializing field"
            }
            val sz = newPointer.offset
            if(sz == BigInteger.ZERO) {
                return err {
                    "Updating free pointer to not be monotonically increasing"
                }
            }
            val loc = newPointer.read
            val obj = s.heap.backing[loc] ?: return err {
                "Attempting to finish allocation for address not in heap"
            }
            if(obj.sz != null) {
                return err {
                    "Attempting to finish up allocation for already allocated object"
                }
            }
            if(s.stack.any { (_, av) ->
                    av is AbstractValue.InitializingPointer && av.read == loc && av.offset > newPointer.offset
                }) {
                return err {
                    "Allocation appears to cut mid block: $newPointer"
                }
            }
            return AbstractState(
                stack = s.stack.updateValues { _, abstractValue ->
                    if(abstractValue !is AbstractValue.InitializingPointer || abstractValue.read != loc || abstractValue.offset != newPointer.offset) {
                        return@updateValues abstractValue
                    }
                    AbstractValue.Const.nondet
                },
                heap = AbstractHeap(
                    s.heap.backing + (loc to obj.copy(sz = newPointer.offset))
                )
            ).toLeft()
        }
        return when(lc.cmd) {
            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                if(lc.cmd.base != TACKeyword.MEMORY.toVar()) {
                    return s.toLeft()
                }
                if(interp(lc.cmd.loc).let {
                        it as? AbstractValue.Const
                    }?.takeIf { it.isConstant }?.c == 0x40.toBigInteger()) {
                    return handleAllocClose {
                        (lc.cmd.value as? TACSymbol.Var)?.toLeft() ?: err {
                            "New value of free pointer is unexpected"
                        }
                    }
                }
                val loc = (lc.cmd.loc as? TACSymbol.Var)?.let(s.stack::get) ?: return err {
                    "Location is not a variable that is mapped"
                }
                if(loc !is AbstractValue.InitializingPointer) {
                    return err {
                        "Location is not an initializing pointer (it is $loc)"
                    }
                }
                // find the abstract object assigned to the free pointer read
                val obj = s.heap.backing[loc.read] ?: return err {
                    "Location ${loc.read} appears unbound in the heap"
                }
                // if we don't have a size yet, this is bad
                if(obj.sz == null) {
                    return err {
                        "Object for ${loc.read} does not have a size yet"
                    }
                }
                // this is unaligned, sus!
                if(loc.offset.mod(EVM_WORD_SIZE) != BigInteger.ZERO) {
                    return err {
                        "Field offset ${loc.offset} for ${loc.read} is unaligned"
                    }
                }
                // double write to an initializing field? sus
                if(loc.offset in obj.initializedSlots) {
                    return err {
                        "Field for $loc is already written? $obj"
                    }
                }
                // verify the value being written in
                when(val v = lc.cmd.value) {
                    is TACSymbol.Const -> {
                        // sus constant for the field
                        if(v.value != BigInteger.ZERO && v.value != 0x60.toBigInteger()) {
                            return err {
                                "Constant value not allowed for default initializer $v for $loc"
                            }
                        }
                        Unit
                    }
                    is TACSymbol.Var -> {
                        val av = s.stack[v] ?: return err {
                            "Attempting to write unknown variable $v into default init"
                        }
                        when(av) {
                            is AbstractValue.Const -> {
                                if(av.wrapped != BigInteger.ZERO && av.wrapped != 0x60.toBigInteger()) {
                                    return err {
                                        "Writing disallowed constant in $v into default init $av"
                                    }
                                }
                            }
                            is AbstractValue.FinalizedPointer -> Unit
                            is AbstractValue.InitializingPointer -> return err {
                                "Attempting to write an initializing pointer into something initializing, this doesn't seem right"
                            }
                        }
                    }
                }
                val newFields = obj.initializedSlots + loc.offset
                val newObject = obj.copy(initializedSlots = newFields)
                if(!newObject.fullyInitialized()) {
                    return s.copy(
                        heap = AbstractHeap(
                            s.heap.backing + (loc.read to newObject)
                        )
                    ).toLeft()
                }
                // otherwise, this is now done "initializing" convert
                return s.copy(
                    stack = s.stack.updateValues { _, v ->
                        if(v !is AbstractValue.InitializingPointer || v.read != loc.read) {
                            return@updateValues v
                        }
                        if(v.offset != BigInteger.ZERO) {
                            return@updateValues AbstractValue.Const.nondet
                        }
                        AbstractValue.FinalizedPointer(loc.read)
                    },
                    heap = AbstractHeap(s.heap.backing + (loc.read to newObject))
                ).toLeft()
            }
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val stepBasicExpression by lazy {
                    s.copy(
                        stack = s.stack + (lc.cmd.lhs to caseSwitch.stepExpression(
                            lhs = lc.cmd.lhs,
                            l = lc.narrow(),
                            whole = Unit,
                            input = s,
                            toStep = s,
                            rhs = lc.cmd.rhs
                        ))
                    ).toLeft()
                }
                if(lc.cmd.lhs == TACKeyword.MEM64.toVar()) {
                    return handleAllocClose get@{
                        if(lc.cmd.rhs !is TACExpr.Sym.Var) {
                            return@get err {
                                "Illegal looking RHS for free pointer"
                            }
                        }
                        lc.cmd.rhs.s.toLeft()
                    }
                } else if(lc.cmd.rhs.equivSym(TACKeyword.MEM64)) {
                    handleAllocation(lc.narrow())
                } else if(lc.cmd.rhs is TACExpr.Vec.Add && lc.cmd.rhs.operandsAreSyms()) {
                    val o1 = lc.cmd.rhs.o1AsTACSymbol()
                    val o2 = lc.cmd.rhs.o2AsTACSymbol()
                    val a1 = interp(o1)
                    val a2 = interp(o2)
                    val (field, offset) = if (a1 is AbstractValue.InitializingPointer && a2 is AbstractValue.Const && a2.isConstant) {
                        a1 to a2.c
                    } else if (a2 is AbstractValue.InitializingPointer && a1 is AbstractValue.Const && a1.isConstant) {
                        a2 to a1.c
                    } else {
                        return stepBasicExpression
                    }
                    s.copy(
                        stack = s.stack + (lc.cmd.lhs to field.copy(
                            offset = offset + field.offset
                        ))
                    ).toLeft()
                } else if(lc.cmd.rhs is TACExpr.Sym.Var) {
                    s.copy(
                        stack = s.stack + (lc.cmd.lhs to (s.stack[lc.cmd.rhs.s] ?: AbstractValue.Const.nondet))
                    ).toLeft()
                } else {
                    stepBasicExpression
                }
            }
            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                if(lc.cmd.base != TACKeyword.MEMORY.toVar()) {
                    return err {
                        "Not willing to handle byteload from this basemap"
                    }
                }
                val loc = interp(lc.cmd.loc)
                if(loc !is AbstractValue.Const || !loc.isConstant || loc.c != 0x40.toBigInteger()) {
                    return err {
                        "Not willing to handle byteload from this location"
                    }
                }
                handleAllocation(lc.narrow())
            }
            is TACCmd.Simple.AssigningCmd -> {
                err {
                    "Not willing to handle assigning this form of assigning command"
                }
            }
            is TACCmd.Simple.AnnotationCmd -> {
                if(lc.cmd.annot.k == META_KEY) {
                    return err {
                        "Hit an internal function hint mid-initialization"
                    }
                }
                s.toLeft()
            }
            is TACCmd.Simple.JumpiCmd -> {
                if(lc.cmd.dst !in cache.revertBlocks && lc.cmd.elseDst !in cache.revertBlocks) {
                    return err {
                        "Unsupported jump commands"
                    }
                }
                s.toLeft()
            }
            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.NopCmd,
            is TACCmd.Simple.JumpdestCmd -> {
                s.toLeft()
            }
            else -> err {
                "Unsupported command"
            }
        }
    }

    context(TACCommandGraph, Worker)
    private fun pushAndAnalyze(
        s: AbstractState,
        t: WorkEdge,
        stack: PersistentStack<NBId>
    ) : NBId? {
        val succ = succ(t.dest.node).singleOrNull() ?: return null
        return analyze(s, succ, stack.push(t.src.node))
    }

    context(TACCommandGraph, Worker)
    private fun analyze(
        s: AbstractState,
        blockId: NBId,
        stack: PersistentStack<NBId>
    ) : NBId? {
        var iter = s
        val block = elab(blockId)
        for(lc in block.commands) {
            when(val res = step(iter, lc)) {
                is Either.Left -> {
                    iter = res.d
                }
                is Either.Right -> {
                    logger.debug { "Initialization search failed with message ${res.d}" }
                    return null
                }
            }
        }
        if(blockId == stack.top) {
            // this is a tail call
            val next = complete.find {
                it.dest.node == blockId
            }
            if(next != null) {
                pushAndAnalyze(
                    iter, next, stack.pop()
                )
            } else if(stack.size == 1) {
                // this is the top level call, is everything initialized?
                if(!iter.heap.backing.all {
                        it.value.fullyInitialized()
                    }) {
                    return null
                }
                return succ(blockId).singleOrNull()
            } else {
                val nxt = succ(blockId).singleOrNull {
                    it !in cache.revertBlocks
                } ?: return null
                return analyze(iter, nxt, stack.pop())
            }
        }
        if(block.commands.last().cmd is TACCmd.Simple.JumpiCmd) {
            val revertSkip = succ(blockId).singleOrNull {
                it !in cache.revertBlocks
            } ?: return null.warnIfNull<NBId?> {
                "Cannot find principle skip target for jumpi"
            }
            return analyze(iter, revertSkip, stack)
        }
        val next = complete.find {
            it.dest.node == blockId
        } ?: return null
        return pushAndAnalyze(iter, next, stack)
    }

    context(TACCommandGraph)
    private fun isInitializerPrefix(
        commands: List<LTACCmd>
    ) : Boolean {
        var iter = AbstractState(
            heap = AbstractHeap(treapMapOf()),
            stack = treapMapOf()
        )
        for(lc in commands) {
            when(val res = step(iter, lc)) {
                is Either.Left -> {
                    iter = res.d
                }
                is Either.Right -> {
                    logger.debug { "Initializer prefix check failed because: ${res.d}" }
                    return false
                }
            }
        }
        return iter.heap.backing.all {
            it.value.fullyInitialized()
        }
    }

    private fun getInitializerInfoOrNull(
        g: TACCommandGraph,
        w: Worker,
        t: WorkEdge
    ) : NBId? {
        return with(g) {
            with(w) {
                pushAndAnalyze(AbstractState(AbstractHeap(treapMapOf()), treapMapOf()), t, persistentStackOf())
            }
        }
    }

    context(TACCommandGraph)
    private fun collectHelperAnnotations(
        block: TACBlock,
        startPoint: Int,
        stackStart: Int?
    ) : ResolutionHints {
        val cmd = block.commands
        var pos = startPoint
        val assigned = mutableMapOf<TACSymbol.Var, TACSymbol>()
        var numArgs : Int? = null
        var inferredId: BigInteger? = null
        var hintId: Int? = null
        val args = mutableMapOf<Int, StackArg>()

        val startLocation = CmdPointer(block.id, startPoint)

        var handledAnnotation : CmdPointer? = null

        val constantAnalysis = MustBeConstantAnalysis(this@TACCommandGraph)

        fun noneResult(msg: String) : ResolutionHints {
            return if(inferredId != null) {
                logger.warn {
                    "At ${cmd[pos]}, failed to parse function hints for ${inferredId!!.toString(16)}: $msg"
                }
                ResolutionHints.FailureFor(msg, "0x${inferredId!!.toString(16)}")
            } else {
                logger.debug {
                    "At ${cmd[pos]}: $msg"
                }
                ResolutionHints.None
            }
        }
        var elemSym: TACSymbol? = null
        var elemIdx: Int? = null

        var symbolsParsed = 0
        var typeLayout : List<InternalArgSort>? = null

        fun parseLayout(
            hint: InternalFunctionHint,
            hintBitWidth: Int,
            parseHint: (Int) -> InternalArgSort
        ) : ResolutionHints? {
            /* parse out the stack layout, if the flag is 2 there are no sanity hints to check */
            if (hint.sym !is TACSymbol.Const) {
                return noneResult("Layout hint was not a constant ${hint.sym}")
            }
            val layout = Array<InternalArgSort?>(numArgs!!) { null }
            var outputPtr = numArgs!! - 1
            var symIt = hint.sym.value
            val layoutMask = (BigInteger.TWO.pow(hintBitWidth) - BigInteger.ONE)
            while (outputPtr >= 0) {
                val flag = symIt.and(layoutMask).toInt()
                if (flag == 0) {
                    return noneResult("Layout hint underflow at $outputPtr ${hint.sym}")
                }
                layout[outputPtr--] = parseHint(flag)
                symIt = symIt.shiftRight(hintBitWidth)
            }
            if (symIt != BigInteger.ZERO) {
                return noneResult("Layout hint overflow for ${hint.sym} (remaining: $symIt)")
            }
            // This is safe: when the loop above exits we will have written a non-null value to each array cell
            typeLayout = listOf(*layout).monadicMap { it } ?: error("Got nulls in $layout")
            return null
        }

        val noHandledAnnotation by lazy {
            noneResult("Could not find handled annotation result")
        }

        var expectCodeCopySummary : TACCmd.Simple.ByteLongCopy? = null

        while(pos < cmd.size) {
            val c = cmd[pos]
            if(c.cmd is TACCmd.Simple.AnnotationCmd) {
                if (c.cmd.annot.k == BLOCK_SOURCE_INFO) {
                    // Just skip over any source info annotations
                    pos++
                    continue
                } else if (c.cmd.annot.k != META_KEY) {
                    return noneResult("encountered invalid annotation ${c.cmd.annot}")
                }
                val hint = c.cmd.annot.v as InternalFunctionHint
                // reconstruct the original key
                if(hintId != null && hint.id != hintId) {
                    return noneResult("Mismatched hint ids: $hintId vs ${hint.id}")
                }
                hintId = hint.id
                /*
                  what do we expect to see at this point?
                  in order:
                  1. the function id (separate from the hint id)
                  2. the number of arguments
                  3. the arguments, in program order
                 */
                @Suppress("ConvertTwoComparisonsToRangeCheck")
                if(inferredId == null) {
                    /*
                      flag 0 is the id, but that's not what we have here
                     */
                    if(hint.flag != 0) {
                        return noneResult("Invalid initial flag ${hint.flag}")
                    }
                    handledAnnotation = c.ptr
                    inferredId = constantAnalysis.mustBeConstantAt(c.ptr, hint.sym)
                        ?: return noneResult("Expected constant initial value, found ${hint.sym}")
                } else if(numArgs == null) {
                    /*
                      argument count flag is 1, and we expect to see that here,  but that's not what we have
                     */
                    if(hint.flag != 1) {
                        return noneResult("Expected arg number flag, found ${hint.flag}")
                    }
                    numArgs = constantAnalysis.mustBeConstantAt(c.ptr, hint.sym)?.toInt()
                        ?: return noneResult("Expected constant-valued arg number, found ${hint.sym}")
                } else if(hint.flag == 2 || hint.flag == 3) {
                    /*
                      Parse the deprecated format for stack layout, see below for the extended format,
                       if the flag is 2 there are no sanity hints to check
                     */
                    (parseLayout(
                        hint, hintBitWidth = 2
                    ) { flag ->
                        when (flag) {
                            1 -> InternalArgSort.SCALAR
                            2 -> InternalArgSort.CALLDATA_ARRAY_LENGTH
                            3 -> InternalArgSort.CALLDATA_ARRAY_ELEMS
                            else -> `impossible!`
                        }
                    })?.let { fail ->
                        return fail
                    }
                    if(hint.flag == 2) {
                        // we are all done here
                        return ResolutionHints.ModifierInfo(
                            internalId = "0x${inferredId.toString(16)}",
                            typeLayout!!,
                            null,
                            handledAnnotation = handledAnnotation ?: return noHandledAnnotation,
                            startLocation = startLocation
                        )
                    }
                } else if(hint.flag == 4 || hint.flag == 5) {
                    (parseLayout(hint, hintBitWidth = 3) { flag ->
                        when(flag) {
                            1 -> InternalArgSort.SCALAR
                            2 -> InternalArgSort.CALLDATA_ARRAY_LENGTH
                            3 -> InternalArgSort.CALLDATA_ARRAY_ELEMS
                            4 -> InternalArgSort.CALLDATA_POINTER
                            else -> error("Invalid argument flag $flag ${hint.sym}")
                        }
                    })?.let { fail ->
                        return fail
                    }
                    if(hint.flag == 4) {
                        // as in the 2 case above, we're done here
                        return ResolutionHints.ModifierInfo(
                            internalId = "0x${inferredId.toString(16)}",
                            typeLayout!!,
                            null,
                            handledAnnotation = handledAnnotation ?: return noHandledAnnotation,
                            startLocation = startLocation
                        )
                    }
                } else if(hint.flag >= 0x6000 && hint.flag < 0x7000) {
                    if(typeLayout == null) {
                        return noneResult("Got modifier sanity hint, but no type layout yet")
                    }
                    // same case as below
                    if(!(hint.sym is TACSymbol.Var && hint.sym in assigned && assigned[hint.sym] is TACSymbol.Var) && !(
                        hint.sym is TACSymbol.Var && stackStart != null && hint.sym.stackHeight()?.let {
                            it >= stackStart
                        } == true
                        )) {
                        return noneResult("Argument hint was not a symbol we had seen copied")
                    }
                    val argumentSymbol = (assigned[hint.sym] as? TACSymbol.Var) ?: hint.sym
                    val hintSort = hint.flag ushr 8 and 3
                    val sort = if(hintSort == 1) {
                        InternalArgSort.CALLDATA_ARRAY_ELEMS
                    } else if(hintSort == 2) {
                        InternalArgSort.CALLDATA_POINTER
                    } else {
                        InternalArgSort.SCALAR
                    }
                    val offset = (hint.flag and 0xff)
                    return ResolutionHints.ModifierInfo(
                        internalId = "0x${inferredId.toString(16)}",
                        typeLayout!!,
                        Triple(offset, sort, argumentSymbol),
                        handledAnnotation = handledAnnotation ?: return noHandledAnnotation,
                        startLocation = startLocation
                    )
                } else {
                    /*
                      Argument k is encoded with flag 4096 + k. In other words, the upper 4 bits of the flag must
                      be b0001.
                     */
                    val upper4Bit = hint.flag ushr 12
                    if(upper4Bit > 5 && upper4Bit != 7 && upper4Bit != 8) {
                        return noneResult("Unrecognized flag format ${hint.flag}")
                    }
                    val argOffs = (hint.flag and 0xfff)
                    if(argOffs in args) {
                        return noneResult("Already parsed argument number $argOffs")
                    }
                    // must be a placeholder encoding
                    if(upper4Bit == 4 || upper4Bit == 5 || upper4Bit == 8) {
                        if (hint.sym != TACSymbol.lift(0)) {
                            return noneResult("Found non-zero value for placeholder arg ${hint.flag}")
                        }
                    } else if(hint.sym is TACSymbol.Const) {
                        // intentionally left blank
                    // If the symbol being logged has not been assigned yet, do a sanity check that we are logging
                    // (and by extension, immediately popping) an argument on the stack (this happens for otherwise unused
                    // arguments)
                    } else if(hint.sym !is TACSymbol.Var || !((hint.sym in assigned && assigned[hint.sym] is TACSymbol.Var) ||
                            (stackStart != null && hint.sym.stackHeight()?.let {
                                it >= stackStart
                            } == true))) {
                        return noneResult("Hint value is not an argument symbol: ${hint.sym} -> ${assigned[hint.sym]}")
                    }
                    val argSym = (assigned[hint.sym] as? TACSymbol.Var) ?: (hint.sym as? TACSymbol.Var)
                    if(upper4Bit == 2) {
                        if(elemSym == null || elemIdx == null) {
                            return noneResult("Encountered a decomposed calldata len, but haven't yet encountered elem")
                        }
                        if (elemIdx != argOffs) {
                            return noneResult("Mismatched len/offset positions, $elemIdx vs $argOffs")
                        }
                        args[argOffs] = StackArg.DecomposedArray(
                            lenSym = argSym!!,
                            offsetSym = elemSym
                        )
                        elemSym = null
                        elemIdx = null
                        symbolsParsed += 2
                    } else if(upper4Bit == 3) {
                        if(elemSym != null || elemIdx != null) {
                            return noneResult("Found a decomposed calldata elem, but alaredy in a state where we've seen one")
                        }
                        elemSym = argSym ?: (hint.sym as? TACSymbol.Const) ?: error("Impossible type hierarchy ${hint.sym}")
                        elemIdx = argOffs
                    } else if(upper4Bit == 4) {
                        if (elemSym != null || elemIdx != null) {
                            return noneResult("Outstanding elem symbol $elemSym @ $elemIdx")
                        }
                        args[argOffs] = StackArg.ArrayPlaceHolder
                        symbolsParsed += 2
                    } else if (upper4Bit == 5 || upper4Bit == 8) {
                        if (elemSym != null || elemIdx != null) {
                            return noneResult("Outstanding elem symbol $elemSym @ $elemIdx")
                        }
                        args[argOffs] = if(upper4Bit == 5) { StackArg.ScalarPlaceHolder } else { StackArg.CalldataPointerPlaceHolder }
                        symbolsParsed += 1
                    } else {
                        check(upper4Bit == 1 || upper4Bit == 7) {
                            "Invalid upper 4 bit $upper4Bit, validation failed?"
                        }
                        if (elemSym != null || elemIdx != null) {
                            return noneResult("Did not find len symbol following encoding of $elemSym @ $elemIdx")
                        }
                        symbolsParsed++
                        if(argSym == null) {
                            check(hint.sym is TACSymbol.Const)
                            args[argOffs] = StackArg.ConstantValue(hint.sym)
                        } else if(upper4Bit == 1) {
                            args[argOffs] = StackArg.Scalar(argSym)
                        } else {
                            args[argOffs] = StackArg.CalldataPointer(argSym)
                        }
                    }
                }
            } else if(c.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                /*
                  Only other option, symbol assignments
                 */
                val symbol = when(val r = c.cmd.rhs) {
                    is TACExpr.Sym.Var -> {
                        /*
                          if r.s is not in our assigned variables, we are copying from some stack location, in which
                          case we MUST be copying an argument value. if we don't know the argument locations
                          yet, save the read value in seenReads, otherwise, check the heights now.
                         */
                        if(r.s !in assigned) {
                            r.s
                        } else {
                            assigned[r.s]!!
                        }
                    }
                    is TACExpr.Sym.Const -> {
                        r.s
                    }
                    else -> return noneResult("Illegal RHS form ${c.cmd.rhs}")
                }
                assigned[c.cmd.lhs] = symbol
            } else if(c.cmd is TACCmd.Simple.NopCmd || c.cmd is TACCmd.Simple.LabelCmd) {
                pos++
                continue
            } else if(c.cmd is TACCmd.Simple.ByteLongCopy) {
                if (c.cmd.meta.get(TACMeta.CONSTANT_SCALARIZATION)?.isInternalAnnotationConstant() == true) {
                    if (expectCodeCopySummary != null) {
                        return noneResult("Already seen scalarized long copy but no summary")
                    } else {
                        expectCodeCopySummary = c.cmd
                        pos++
                        continue
                    }
                } else {
                    return noneResult("invalid command format")
                }
            } else if(c.cmd is TACCmd.Simple.SummaryCmd && c.cmd.summ is CodecopyOpcodeSummary && expectCodeCopySummary != null) {
                val summ = c.cmd.summ
                if(summ.destOffset != expectCodeCopySummary.dstOffset || summ.length != expectCodeCopySummary.length || summ.offset != expectCodeCopySummary.srcOffset) {
                    return noneResult("invalid command format")
                }
                expectCodeCopySummary = null
                pos++
                continue
            } else {
                return noneResult("invalid command format")
            }
            if(inferredId != null && numArgs != null && symbolsParsed == numArgs) {
                /*
                 We have inferred everything, so we return back the embedded info we parsed out.
                 */
                return ResolutionHints.EmbeddedInfo(
                    internalId = "0x${inferredId.toString(16)}",
                    args = args.entries.sortedBy {
                        it.key
                    }.map {
                        it.value
                    },
                    handledAnnotation = handledAnnotation ?: return noHandledAnnotation,
                    startLocation = CmdPointer(block = block.id, pos = startPoint)
                )
            }
            pos++
        }
        return noneResult("Did not finish parsing hints")
    }

    /**
     * Parse out the hints inserted by the internal function annotator. These hints take the form of memory writes at
     * implausibly large offsets (so large it would incur a gas cost several orders of magnitude larger than the current
     * gas limit), that have been translated to instances of the [InternalFunctionHint] annotations.
     *
     * These annotations are expected to occur at the "beginning" of the function, before any non-trivial computation
     * takes place. This analysis is very conservative, preferring to fail identifying a function than misclassify
     * a function that has been mysteriously inlined into another block. Thus, only a limited subset of commands
     * are allowed to appear in the block before annotations are complete. They are:
     * 1. variable assignments whose RHS is either a variable or a constant,
     * 2. the helper annotation commands, and
     * 3. the initial jumpdest command
     *
     * Anything else causes the parsing process to fail.
     *
     * The parsing process is also *very* picky about the variables that may be assigned. It expects all assignments
     * to occur in the "scratch" space, i.e., the space after the arguments passed on the stack to the function.
     * In other words, we may *not* mutate the arguments on the stack. Further, all variables being read must be
     * a copy of an argument value, or a constant.
     */
    context(TACCommandGraph)
    private fun collectHelperAnnotations(block: TACBlock) : ResolutionHints {
        val cmd = block.commands
        if(cmd[0].cmd !is TACCmd.Simple.JumpdestCmd) {
            logger.debug { "Block ${block.id} does not start with jumpdest..." }
            return ResolutionHints.None
        }
        return collectHelperAnnotations(block, 1, block.id.stkTop)
    }

}
