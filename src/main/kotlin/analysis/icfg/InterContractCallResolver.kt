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

@file:SuppressRemapWarning
package analysis.icfg

import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.narrow
import analysis.pta.ArrayType
import analysis.pta.HeapType
import analysis.pta.abi.DecoderAnalysis
import analysis.pta.abi.DecoderAnalysis.Companion.sizeInArray
import analysis.pta.abi.ObjectPathGen
import analysis.pta.abi.UnitPath
import com.certora.collect.*
import config.Config
import evm.SighashInt
import log.Logger
import log.LoggerTypes
import scene.*
import utils.*
import vc.data.*
import java.math.BigInteger
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE

private val logger = Logger(LoggerTypes.INLINER)

data class CallStackItem(val ref: MethodRef, val callType: TACCallType?)
private typealias InternalCallStack = TreapList<CallStackItem>

object InterContractCallResolver {

    /**
     * Describes how to infer the value of `this` at a call site.
     */
    sealed interface ThisInference {
        /**
         * Given the current [ThisInference] state, and a set of [StackPushRecord] that
         * describe the virtual call stack at some call-site, get the value of this at the
         * call site.
         */
        fun inferAt(stackAt: Collection<StackPushRecord>) : ContractId

        /**
         * [v] is the known "host" contract of the code in which all call-sites are found; in other words,
         * [v] is the value of `this` at the bottom of the call stack.
         * Consider some call-site L.
         * If there exists any non-delegate call in the call stack at L, the
         * last such push record defines the value of this at L. Otherwise, the call-site L is reached
         * through zero or more delegate calls, all of which preserve `this`, and thus
         * this must be [v].
         */
        data class Explicit(val v: ContractId) : ThisInference  {
            override fun inferAt(stackAt: Collection<StackPushRecord>): ContractId {
                return stackAt.lastOrNull {
                    it.ref is MethodRef &&
                    it.callType != TACCallType.DELEGATE
                }?.ref?.let { it as MethodRef }?.contractId ?: v
            }
        }

        /**
         * Find the last non-delegate call in the call stack at a call-site: this must be the concrete value of this.
         * If there is no such call site, inference fails. This is used exclusively after rule inlining, where all
         * calls that appear within a rule body must include some direct call (as only direct, non-delegate calls from
         * rules to EVM are allowed).
         */
        data object Infer : ThisInference {
            override fun inferAt(stackAt: Collection<StackPushRecord>): ContractId {
                return stackAt.lastOrNull {
                    it.ref is MethodRef &&
                    it.callType != TACCallType.DELEGATE
                }?.ref?.let { it as MethodRef }?.contractId ?: throw IllegalStateException("Cannot find value of `this` in stack $stackAt")
            }
        }
    }

    sealed class CallGraphNode {
        open fun prettyPrint(scene : IScene): String = toString()

        /**
         * Represents an unresolved call. This is (initially) constructed directly from the callsummary nodes that
         * appear in a coretacprogram, but as inlining is resolved, symbolic arguments contract addresses
         * may be replaced with concrete addresses, introducing further opportunities for inlining.
         */
        data class CallNode(
            /**
             * The unique id of the call summary. Lifted directly from [CallSummary.summaryId]
             */
            val id: Int,
            /**
             * The (potentially) symbolic address for the target of this call
             */
            val callTarget: Set<CallGraphBuilder.CalledContract>,
            /**
             * The (scalar) arguments that may be passed to the inlined function
             * being called. If this node is resolved to a method m, then, e.g.,
             * all references to the symbolic address "SymbolicInput(32)" in m
             * will be replaced with the value of `symbolicArgs[32]`. NB that
             * this contract address may yet be another instance of `SymbolicInput`,
             * but it refers to the argument to the method that contain THIS particular
             * call.
             */
            val symbolicArgs: Map<BigInteger, CallGraphBuilder.CalledContract>,
            /**
             * Possible sighashes this call was resolved to
             */
            val sigHash: Set<BigInteger?>,
            val where: CmdPointer,
            val inSize: TACSymbol,
            val summary: CallSummary,
            val abiArgs: ABIArgumentEncoding?,
            val stackSummary: Collection<StackPushRecord>,
        ) : CallGraphNode() {
            init {
                check(callTarget.isNotEmpty()){"Call targets for a call node should never be empty."}
            }

            override fun prettyPrint(scene: IScene): String {
                val sighash = sigHash.singleOrNull() ?: return "unknown sighash of type ${summary.callType}"
                val implementors = scene.getMethodImplementors(sighash)
                val anImplementor = implementors.firstOrNull()
                val method = anImplementor?.let { scene.getMethod(it.instanceId, sighash) } ?: "unknown ($sighash)"
                return "Call node for method $method implemented by {${implementors.map{it.name}}} of type ${summary.callType}"
            }

            context(IScene)
            private fun prettyPrintCallStack(callstack: InternalCallStack) =
                callstack.map { "<${it.ref.toString(this@IScene)},${it.callType}>"}

            context(IScene, MethodNodeManager, InliningDecisionManager)
            override fun resolveInContext(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: MutableMap<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>,
                callStack: InternalCallStack,
                thisState: ThisInference
            ): Boolean {
                if(!this.canResolve()) {
                    return false
                }
                val effectiveCallStack = callStack + this.stackSummary.mapNotNull { if (it.ref is MethodRef) { CallStackItem(it.ref, it.callType) } else { null } }

                val thisAddress = thisState.inferAt(stackSummary)
                logger.info { "Resolving ${this@CallNode.prettyPrint(this@IScene)}, stack ${prettyPrintCallStack(effectiveCallStack)}"}

                /*
                 * If we're here, we've passed canResolve, which means our callee is non null and returns a constant
                 * address, we also know that the callTarget must only contain exactly one element. Otherwise canResolve should have returned false.
                 */
                val target = callTarget.singleOrNull() ?: error("Expected exactly one element in callTarget set, got ${callTarget}")
                val callee = when (target) {
                    is CallGraphBuilder.CalledContract.FullyResolved.SelfLink -> {
                        thisAddress
                    }
                    is CallGraphBuilder.CalledContract.FullyResolved -> target.contractId
                    else -> throw IllegalStateException("Illegal callee $target, should be a fully resolved one")
                }
                val ref = if (ContractUniverse.ETHEREUM.isPrecompiled(callee) && // precompiled
                    // ecrecover specific:
                    ((this.inSize == TACSymbol.lift(0x80) && callee == PrecompiledContractCode.PrecompiledContractCodeKnownInputSize.ecrecover.address)
                        || callee == PrecompiledContractCode.sha256.address || callee == PrecompiledContractCode.identity.address
                        )
                ) {
                    // call the precompiled, if supported
                    MethodRef(
                        callee,
                        sigHash = null,
                        attr = MethodAttribute.Unique.Whole
                    )
                } else if(sigHash.size == 1) {
                    val sig = sigHash.single()
                    if (sig != null) {
                        val ref = MethodRef(callee, sigHash = SighashInt(sig), attr = MethodAttribute.Common)
                        if (ref !in this@MethodNodeManager) {
                            // The method is fully resolved, yet doesn't exist in the contract. EVM would call the
                            // fallback in this case, so we do too.
                            MethodRef(callee, sigHash = null, attr = MethodAttribute.Unique.Fallback)
                        } else {
                            ref
                        }
                    } else {
                        MethodRef(callee, sigHash = null, attr = MethodAttribute.Unique.Fallback)
                    }
                } else {
                    check(sigHash.size > 1 && inSize is TACSymbol.Const)
                    val contract = getContract(callee)
                    sigHash.mapNotNull {
                        (contract.getMethodOrFallback(it) as? ITACMethod)?.takeIf {
                            it.evmExternalMethodInfo != null
                        }
                    }.singleOrNull {
                        ((it.evmExternalMethodInfo!!.argTypes.size * 32) + 4).toBigInteger() == inSize.value
                    }?.toRef() ?: run {
                        /*
                           We couldn't resolve correctly, so we invalidate the callee (which guarantees
                           this node will never be revisited) and then return.

                           Note the "update reference to self" pattern
                         */
                        container[selfIndex] = this.copy(
                            callTarget = setOf(CallGraphBuilder.CalledContract.Invalidated(callee, target))
                        )
                        return true
                    }
                }
                val scene = this@IScene
                val methods = this@MethodNodeManager
                if (effectiveCallStack.count { it.ref == ref } > Config.ContractRecursionLimit.get()) {
                    logger.info {
                        "When resolving call @ $where to ${ref.toString(scene) ?: ref}, found that we already have it in the stack " +
                            "more times than allowed by ${Config.ContractRecursionLimit.userFacingName()}, (set to ${Config.ContractRecursionLimit.get()}): ${
                                callStack.map {
                                    it.ref.toString(scene) ?: it.ref
                                }
                            }"
                    }
                    container[selfIndex] = ResolvedRecursionLimit(
                        originalNode = this
                    )
                    return true
                }
                if(ref !in methods) {
                    /*
                      We couldn't get the fresh call graph node that represents the call/return behavior of [ref]. Without
                      it we can't do much.
                     */
                    // WELP
                    logger.warn { "When resolving call @ $where to ${ref.toString(scene) ?: ref}, " +
                            "found that it is not available as a method or as a precompiled contract. Erasing it." }
                    container[selfIndex] = this.copy(
                        // TODO: maybe make this part of the canResolve check...?
                        callTarget = setOf(CallGraphBuilder.CalledContract.Invalidated(callee, target)) // invalidate the callee, we can't actually resolve
                    )
                    return true
                }

                /* lift you up until you break */
                val meth = methods[ref] ?: throw IllegalStateException("Could not find ref $ref when resolving a call @ $where")

                val resolved = ref.resolveIn(this@IScene) as TACMethod
                if(!this@InliningDecisionManager.shouldInline(
                        thisAtCall = thisAddress,
                        summ = this.summary,
                        callee = resolved
                    )) {
                    logger.info {"Not inlining ${ref.toString(this@IScene)}"}
                    if (target is CallGraphBuilder.CalledContract.FullyResolved.SelfLink) {
                        logger.debug { "We resolved a self-link, so let's update as fully resolved" }
                        container[selfIndex] = this@CallNode.copy(
                            callTarget = setOf(CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(callee))
                        )
                    }
                    return false
                }
                /**
                 * At this point, we are committed to inlining this function (unless an exception is thrown).
                 * Notify the decision manager that the inlining will be occurring.
                 */
                this@InliningDecisionManager.update(thisAtCall = thisAddress, summ = this.summary, callee = resolved)

                /**
                 * As described above, after resolving the call represented by `this` to a method, we can propagate
                 * information about the *concrete* arguments at this callsite into the body of the resolved method.
                 *
                 * This convenience function performs that propagation: a reference to the Nth parameter of the *CALLED*
                 * method is replaced with the Nth argument of *THIS* specific call.
                 *
                 * In other words, we have unlimited context sensitivity when resolving method calls.
                 *
                 * Q: What about recursion
                 * A: read the bit about the call stack again, very carefully
                 */
                /*
                   If we resolve multiple calls to the same method, the ids of the call summaries
                   in the *body* of that method will no longer be unique, which is extremely problematic.

                   idMapping provides a substitution that generates new, guaranteed fresh ids for the callsummaries (and
                   thus the CallNodes) in the resolved method.
                 */
                val idMapping = mutableMapOf<Int, Int>().also { m ->
                    meth.callNodes.forEach {
                        m[it.id] = Allocator.getFreshId(Allocator.Id.CALL_SUMMARIES)
                    }
                }
                fun CallGraphBuilder.CalledContract.substCallInScope() : CallGraphBuilder.CalledContract =
                    if (this is CallGraphBuilder.CalledContract.SymbolicInput) {
                        this@CallNode.symbolicArgs[this.offset].let {
                            if (it is CallGraphBuilder.CalledContract.FullyResolved.SelfLink) {
                                CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(thisAddress)
                            } else {
                                it
                            }
                        } ?: CallGraphBuilder.CalledContract.Unresolved
                    } else if (this is CallGraphBuilder.CalledContract.SymbolicOutput && this.which in idMapping) {
                        this.copy(
                            which = idMapping[this.which]!!
                        )
                    } else {
                        this
                    }

                /**
                 * Associates with buffer access paths in the *callee* the concrete sighash found (if any) in
                 * the buffer passed by the CALLER at the at path. We do this by traversing the input arguments, building up
                 * the buffer access paths along the way; whenever we reach a value with an associated sighash we record the
                 * built-up buffer access path in this map. In the callee, we look for symbolic sig resolutions that reference
                 * one of the BAP's found during this traversal in the caller; which provides the wiring up we want.
                 */
                val symbolicSigResolution = mutableMapOf<DecoderAnalysis.BufferAccessPath, BigInteger>()
                if(abiArgs?.encodedArgs != null) {
                    for((offs, abiValue) in abiArgs.encodedArgs) {
                        if(abiValue !is ABIValue.Symbol) {
                            continue
                        }
                        if(!abiValue.type.isDynamic()) {
                            continue
                        }
                        val startRoot = DecoderAnalysis.BufferAccessPath.Deref(
                            DecoderAnalysis.BufferAccessPath.Offset(offset = offs, base = DecoderAnalysis.BufferAccessPath.Root)
                        )
                        tailrec fun traversePath(path: ObjectPathGen<UnitPath>, k: (HeapType, DecoderAnalysis.BufferAccessPath) -> Unit) {
                            when(path) {
                                is ObjectPathGen.ArrayLength -> return traversePath(path.parent) { ty, bap ->
                                    if (bap is DecoderAnalysis.BufferAccessPath.Deref && ty is ArrayType) {
                                        k(HeapType.Int, DecoderAnalysis.BufferAccessPath.Offset(offset = BigInteger.ZERO, base = bap))
                                    }
                                }
                                is ObjectPathGen.Field -> {
                                    return traversePath(path.parent) cont@{ ty, bap ->
                                        if(ty !is HeapType.OffsetMap || !ty.isDynamic() || bap !is DecoderAnalysis.BufferAccessPath.Deref) {
                                            return@cont
                                        }
                                        var it = BigInteger.ZERO
                                        var fldOffset = BigInteger.ZERO
                                        while(it < path.offset) {
                                            val fTy = ty.fieldTypes[fldOffset] ?: return@cont
                                            fldOffset += fTy.sizeInArray()
                                            it += EVM_WORD_SIZE
                                        }
                                        val targetFieldTy = ty.fieldTypes[it]?.takeIf { it.isDynamic() } ?: return@cont
                                        k(targetFieldTy, DecoderAnalysis.BufferAccessPath.Deref(
                                            DecoderAnalysis.BufferAccessPath.Offset(
                                                offset = fldOffset,
                                                base = bap
                                            )
                                        ))
                                    }
                                }
                                is ObjectPathGen.Root -> k(abiValue.type, startRoot)
                                is ObjectPathGen.StaticArrayField,
                                is ObjectPathGen.ArrayElem -> return
                            }
                        }
                        for((path, res) in abiValue.childEncodings) {
                            if(res.sighash == null) {
                                continue
                            }
                            traversePath(path) { ty, bap ->
                                if(ty is HeapType.ByteArray) {
                                    symbolicSigResolution[bap] = res.sighash
                                }
                            }
                        }
                    }
                }

                val children = mutableListOf<CallGraphNode>()
                val truncatedStacks = mutableMapOf<CmdPointer, TruncateStack>()
                val grouping = effectiveCallStack.groupingBy { it.ref }.eachCount().toTreapMap()
                outer@for(it in meth.callNodes) {
                    val currCounts = grouping.builder()
                    for(summ in it.stackSummary) {
                        if (summ.ref !is MethodRef) { continue }
                        val invocationCount = (currCounts[summ.ref] ?: 0)
                        if(invocationCount > Config.ContractRecursionLimit.get()) {
                            truncatedStacks[summ.ptr] = TruncateStack(summ.ptr)
                            continue@outer
                        }
                        currCounts.merge(summ.ref, 1) { a, b ->
                            a + b
                        }
                    }
                    val id = idMapping[it.id]!!
                    /**
                     * For each call in the newly resolved method, give it its new call, and propagate information
                     * about the arguments of this call into the symbolic arguments.
                     */
                    children.add(it.copy(
                        id = id,
                        symbolicArgs = it.symbolicArgs.map { kv ->
                            kv.key to kv.value.substCallInScope()
                        }.toMap(),
                        callTarget = it.callTarget.mapToSet { it.substCallInScope()},
                        sigHash = if(it.sigHash.isEmpty() && it.summary.symbolicSigResolution != null) {
                            symbolicSigResolution[it.summary.symbolicSigResolution]?.let(::setOf) ?: it.sigHash
                        } else {
                            it.sigHash
                        }
                    ))
                }
                children.addAll(truncatedStacks.values)
                /**
                 * Now that we've resolved the call represented by this, we can use the return information
                 * from the called method to update other call nodes that use contracts returned from this call.
                 */
                val d = meth.returnSummary?.mapNotNull { (k, v) ->
                    if(v is CallGraphBuilder.CalledContract.SymbolicOutput) {
                        check(v.which in idMapping) {
                            "In ${ref.toString(scene)}: Encountered output $v for $k that refers to a call that doesn't appear"
                        }
                        /*
                          Of course, the callee could return contracts returned from calls within ITS body. To track these,
                          update the id of the call node with the forward substition we computed above.
                         */
                        k to v.copy(
                            which = idMapping[v.which]!!
                        )
                    } else {
                        /*
                          And the callee could return contracts we passed into it, so perform that substitution here as well.
                         */
                        k to v.substCallInScope().let {
                            if (it is CallGraphBuilder.CalledContract.FullyResolved.SelfLink) {
                                CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(thisAddress)
                            } else {
                                it
                            }
                        }

                    }
                }?.toMap()
                /* record the substition information for references to this call's returned contracts for later propagation.
                 */
                substitutions[this.id] = d
                val res = ResolvedCall(
                    resolveTo = ref,
                    origNode = this,
                    children = children,
                    calleeABI = meth.abiInput?.copy(
                        callSiteReads = meth.abiInput.callSiteReads.mapKeys { (it, _) ->
                            idMapping[it]!!
                        }
                    ),
                    withStack = this.stackSummary,
                    thisAtCall = thisAddress,
                    storageMapper = getStorageRewriteStrategy()
                )
                container[selfIndex] = res
                return true
            }

            private tailrec fun makeSubstitution(v: CallGraphBuilder.CalledContract, sigma: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>) : CallGraphBuilder.CalledContract {
                return if(v is CallGraphBuilder.CalledContract.SymbolicOutput && v.which in sigma) {
                    val ret = sigma[v.which]?.get(v.offset)
                    if(ret == null) {
                        CallGraphBuilder.CalledContract.Unresolved
                    } else {
                        makeSubstitution(ret, sigma)
                    }
                } else {
                    v
                }
            }

            override fun substituteReturn(container: MutableList<CallGraphNode>, selfIndex: Int, substitutions: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>) {
                /*
                    We now have new information about the contracts returned by the calls in [substitution]. Propagate that information
                    to ourselves, assuming we use that info.
                 */
                val withSymbolicArgs = if(symbolicArgs.any { (_, v) -> v is CallGraphBuilder.CalledContract.SymbolicOutput && v.which in substitutions }){
                     symbolicArgs.mapNotNull { (k, v) ->
                        k `to?` makeSubstitution(v, substitutions)
                    }.toMap()
                } else{
                    this.symbolicArgs
                }

                val updatedCallees = callTarget.mapToSet {callee ->
                    if(callee is CallGraphBuilder.CalledContract.SymbolicOutput && callee.which in substitutions){
                        makeSubstitution(callee, substitutions)
                    } else{
                        callee
                    }
                }
                container[selfIndex] = this.copy(
                    callTarget = updatedCallees,
                    symbolicArgs = withSymbolicArgs
                )
            }

            /**
             * @returns true if the [CallNode] can be resolved.
             * That is if:
             * - the call Target set is exactly of size 1.
             * - callee exists and is associated with an address
             * - the [CallNode] should resolve (currently used to distinguish between different call-types, i.e. delegate vs. non-delegate)
             * - there is a signature, or there's no signature and we detect a 0-calldatasize fallback,
             *      or multiple signatures with a known input size
             *      or a call to one of the supported precompiles
             */
            context(InliningDecisionManager)
            override fun canResolve(): Boolean {
                val callee = callTarget.singleOrNull()
                if(callee !is CallGraphBuilder.CalledContract.FullyResolved || callee.contractId == BigInteger.ZERO) {
                    return false
                }
                if(!this@InliningDecisionManager.getStatelessManager().eligibleForInlining(this.summary, callee.contractId)) {
                    return false
                }
                if(sigHash.size == 1) {
                    return true
                }
                if(sigHash.size > 1 && inSize is TACSymbol.Const) {
                    return true
                }
                return PrecompiledContractCode.getPrecompiledImplemented(callee.contractId).let { precompiled ->
                    precompiled != null &&
                        // either a precompiled doesn't have a known input size,
                        // or it is, and then we need to check that the input size matches the actual input
                        // size
                        (precompiled !is PrecompiledContractCode.PrecompiledContractCodeKnownInputSize ||
                            precompiled.expectedInputSize == (inSize as? TACSymbol.Const)?.value)
                    }
            }

            override fun callLocation(): CmdPointer {
                return where
            }
        }

        class ResolvedCall(
            val resolveTo: MethodRef,
            val origNode: CallNode,
            val children: MutableList<CallGraphNode>,
            val calleeABI: ABICallConvention.ABIExpected?,
            val withStack: Collection<StackPushRecord>,
            val thisAtCall: ContractId,
            val storageMapper: StorageRewriteStrategy
        ) : CallGraphNode() {
            override fun prettyPrint(scene: IScene): String {
                return resolveTo.sigHash?.let {sighash -> "${scene.getMethod(resolveTo.contractId, sighash.n)}"} ?: super.prettyPrint(scene)
            }

            private val thisInBody : ContractId get() = if(this.origNode.summary.callType == TACCallType.DELEGATE) {
                this.thisAtCall
            } else {
                resolveTo.contractId
            }

            val storageRewriter get() = storageMapper.getStorageRewriter(thisAtCall, this.origNode.summary.callType)

            context(IScene, MethodNodeManager, InliningDecisionManager)
            override fun resolveInContext(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: MutableMap<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>,
                callStack: InternalCallStack,
                thisState: ThisInference
            ): Boolean {
                var rewrote = false
                for(i in 0..children.lastIndex) {
                    val childEffect = children[i].resolveInContext(
                        container = this.children,
                        selfIndex = i,
                        substitutions = substitutions,
                        callStack = callStack + withStack.mapNotNull { if (it.ref is MethodRef) { CallStackItem(it.ref, it.callType) } else { null } } + CallStackItem(resolveTo, origNode.summary.callType),
                        thisState = ThisInference.Explicit(thisInBody)
                    )
                    rewrote = childEffect || rewrote
                }
                return rewrote
            }

            override fun substituteReturn(container: MutableList<CallGraphNode>, selfIndex: Int, substitutions: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>) {
                for(i in 0..children.lastIndex) {
                    children[i].substituteReturn(this.children, i, substitutions)
                }
            }

            context(InliningDecisionManager)
            override fun canResolve(): Boolean =
                children.any { it.canResolve() }

            override fun callLocation(): CmdPointer {
                return origNode.callLocation()
            }

            override fun toString(): String {
                return "Call($resolveTo, $children, [orig: $origNode])"
            }
        }

        data class TruncateStack(
            val originalLocation: CmdPointer
        ) : CallGraphNode() {
            override fun canResolve(): Boolean {
                return false
            }

            override fun callLocation(): CmdPointer {
                return originalLocation
            }

            context(IScene, MethodNodeManager, InliningDecisionManager)
            override fun resolveInContext(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: MutableMap<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>,
                callStack: InternalCallStack,
                thisState: ThisInference
            ): Boolean {
                return false
            }

            override fun substituteReturn(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>
            ) {
                return
            }

        }

        data class ResolvedRecursionLimit(
            val originalNode: CallNode
        ) : CallGraphNode() {
            override fun canResolve(): Boolean {
                return false
            }

            override fun callLocation(): CmdPointer {
                return originalNode.callLocation()
            }

            context(IScene, MethodNodeManager, InliningDecisionManager)
            override fun resolveInContext(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: MutableMap<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>,
                callStack: InternalCallStack,
                thisState: ThisInference
            ): Boolean {
                return false
            }

            override fun substituteReturn(
                container: MutableList<CallGraphNode>,
                selfIndex: Int,
                substitutions: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>
            ) {
                return
            }
        }

        /**
         * Returns whether this [CallGraphNode] is definitely unresolvable (by returning false)
         * or whether it is possible for resolution. The inlining decision manager is accessible as a context
         * parameter
         */
        context(InliningDecisionManager)
        abstract fun canResolve(): Boolean

        /**
         * Return the original [CmdPointer] whose [vc.data.TACCmd.Simple.CallCore] or [ICallCoreSummary]
         * yielded this [CallGraphNode]
         */
        abstract fun callLocation(): CmdPointer

        /**
         * Resolve this CallNode or any callnodes reachable from its children.
         *
         * [MethodNodeManager] is the factory that produces a fresh [CallNode] from the
         * resolved [MethodRef].
         * It is an (unchecked) precondition that that the receiver object of this method
         * must be at index [selfIndex] in [container]; as nodes resolve they "replace themselves"
         * in their parent container. For example, if a call node c at index i in some method m's callNodes
         * can be resolved, the resolveInContext implementation of c will set the element at index `i` to
         * the (newly created) [ResolvedCall] node.
         *
         * As calls are resolved, [analysis.icfg.CallGraphBuilder.CalledContract.SymbolicOutput]
         * instances that refer to the results produced by that call can (and should) be updated
         * to instead use the [AbstractNode.returnSummary] information in the resolved caller.
         *
         * [substitutions] holds this information for propagation to other nodes: suppose [substitutions]
         * maps key k to some map { 0 -> Address(0x1234....) }. This indicates that the call with
         * id k was resolved to some method, and that method is known to *always* return the address 0x1234...
         * as the first element of its return buffer. Thus, other "sibling" calls that use the return value
         * of the call at k as the callee must be updated to record that the callee is known (to wit: the contract
         * with address 0x1234....)
         *
         * [callStack] is the list of activation records "on the stack" when this node is resolved. It is a list of [MethodRef]
         * only, there is no information given about call types.
         *
         * Finally [thisState] is an instance of [ThisInference] which can be used to infer the value of `this` at
         * the call site. NB this does NOT tell you what the value of THIS is within the *callee* for that, see the
         * (private)  [CallGraphNode.ResolvedCall.thisInBody].
         *
         * The [IScene], [MethodNodeManager] and [InliningDecisionManager] are plumbed through as context parameters.
         *
         * Returns true if any resolution changes were made by this function, otherwise returns false.
         */
        context(IScene, MethodNodeManager, InliningDecisionManager)
        abstract fun resolveInContext(
            container: MutableList<CallGraphNode>,
            selfIndex: Int,
            substitutions: MutableMap<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>,
            callStack: InternalCallStack,
            thisState: ThisInference
        ) : Boolean

        /**
         * Perform the propagation of return information as described in the documentation of [resolveInContext].
         * As in the [resolveInContext], [container] and [selfIndex] provide the "coordinates" of the receiver
         * object so as to effect an in-place update.
         *
         * [substitutions] holds a map of [CallNode] ids to the [analysis.icfg.CallGraphBuilder.CalledContract]s
         * (symbolic or otherwise) that are known to be
         * returned by those call. Other nodes that use the return values of said call nodes must update
         * themselves to use these addresses (or clear its callee information, if it turns out the
         * returned data from the referenced call does *not* have called contract information)
         */
        abstract fun substituteReturn(container: MutableList<CallGraphNode>, selfIndex: Int, substitutions: Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>)
    }

    /**
     * Represents a single method in the scene. This method is known to return contracts as
     * described in [returnSummary] (if there is any return information at all). In addition,
     * this method is known to contain [callNodes]. Depending on the bound of [T],
     * some of these call nodes may be resolved (i.e., of type [CallGraphNode.ResolvedCall]),
     * or all may be unresolved [CallGraphNode.CallNode]
     */
    data class AbstractNode<T: CallGraphNode>(
        val returnSummary: Map<BigInteger, CallGraphBuilder.CalledContract>?,
        val callNodes: List<T>,
        val abiInput: ABICallConvention.ABIExpected?
    )

    /**
     * An abstraction for "resolving" a [MethodRef] into a fresh [CallGraphMethodNode]: i.e., a summary
     * both of the calls contained in the body of the [MethodRef] and any storage pointers
     * returned by the body of [MethodRef] (if any). NB by the type bound in [CallGraphMethodNode] all
     * calls are initially unresolved.
     */
    interface MethodNodeManager {
        operator fun contains(m: MethodRef) : Boolean
        operator fun get(m: MethodRef) : CallGraphMethodNode?
    }

    private class CompleteUniverse(private val map: Map<MethodRef, CallGraphMethodNode>) : MethodNodeManager {
        override fun contains(m: MethodRef): Boolean = map.contains(m)
        override fun get(m: MethodRef): CallGraphMethodNode? = map.get(m)
    }

    private class OnDemandNodeBuilder(private val scene: IScene) : MethodNodeManager {
        private val cache = mutableMapOf<MethodRef, CallGraphMethodNode?>()

        override operator fun contains(m: MethodRef) : Boolean  {
            return get(m) != null
        }

         override operator fun get(m: MethodRef) : CallGraphMethodNode? {
             return cache.computeIfAbsent(m) {
                 it.resolveIn(scene)?.let {
                     tacMethodToNode(it)
                 }
             }
         }
    }

    context(IScene, InliningDecisionManager)
    fun resolveCalls(prog: CoreTACProgram) : List<CallGraphNode> {
        val l = (getCallNodes(prog) as List<CallGraphNode>).toMutableList()
        val nodeManager = OnDemandNodeBuilder(
            scene = this@IScene
        )
        var changed = true
        while(l.any {
                    it.canResolve()
                } && changed) {
            changed = resolveInContainer(
                l = l,
                initialStack = StackAndThisManager(treapListOf(), ThisInference.Infer),
                methods = nodeManager,
                returnRewrites = { _ -> }
            )
        }
        return l
    }

    /**
     * A named version of [Pair]<[InternalCallStack], [ThisInference]>, as these are two common, related parameters
     * for [resolveInContainer].
     */
    private data class StackAndThisManager(val initialStack: InternalCallStack, val thisInference: ThisInference)

    context(IScene, InliningDecisionManager)
    private fun resolveInContainer(
        l: MutableList<CallGraphNode>,
        methods: MethodNodeManager,
        initialStack: StackAndThisManager,
        returnRewrites: (Map<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>) -> Unit
    ) : Boolean {
        val subst = mutableMapOf<Int, Map<BigInteger, CallGraphBuilder.CalledContract>?>()
        var resolved = false
        val callStack = initialStack.initialStack
        for(i in 0..l.lastIndex) {
            val n = l[i]
            if(n.canResolve()) {
                val childResolved = with(methods) {
                    n.resolveInContext(l, i, subst, callStack, initialStack.thisInference)
                }
                resolved =  childResolved || resolved
            }
        }
        if(resolved) {
            for(i in 0..l.lastIndex) {
                l[i].substituteReturn(l, i, subst)
            }
            returnRewrites(subst)
        }
        return resolved
    }

    context(IScene, InliningDecisionManager)
    fun resolveCalls(methods: List<ITACMethod>) : Map<MethodRef, MethodNode> {
        val methodNodes = methods.associate { m ->
            m.toRef() to tacMethodToNode(m)
        }

        /**
         * These two maps simulate a map method refs to a "mutable" version of a [MethodNode]. We could
         * simulate this behavior by having a mutable map of [MethodRef] to [MethodNode], and using
         * dataclass copies on every mutation, but this is too much copying for my taste. Thus, we instead
         * "deconstruct" the fields of each [MethodNode] into two maps that contain mutable versions
         * of those fields. When resolving is done, we "zip" back up these fields into the (immutable) [MethodNode]
         * instances returned by this function.
         */
        val callgraph = mutableMapOf<MethodRef, MutableList<CallGraphNode>>()
        val returnInfo = mutableMapOf<MethodRef, MutableMap<BigInteger, CallGraphBuilder.CalledContract>>()
        val abiInfo = mutableMapOf<MethodRef, ABICallConvention.ABIExpected?>()
        methodNodes.forEach { (k, v) ->
            callgraph[k] = (v.callNodes as List<CallGraphNode>).toMutableList()
            returnInfo[k] = v.returnSummary?.toMutableMap() ?: return@forEach
            abiInfo[k] = v.abiInput
        }
        var changed = true
        val universe = CompleteUniverse(methodNodes)
        var round = 0
        while(callgraph.any {
                it.value.any {
                    it.canResolve()
                }
            } && changed) {
            round++
            logger.info {"Resolve calls, round $round"}
            changed = false
            for((host, l) in callgraph) {
                logger.debug { "Resolving ${host.toString(this@IScene)} with children ${l.map { it.prettyPrint(this@IScene) }}"}

                val resolved = resolveInContainer(
                    methods = universe,
                    l = l,
                    /**
                     * The initial stack as follows: when performing this inlining, *any* call node reached must AT LEAST
                     * be within the body of `host`, so *someone* *somewhere* must have called the host method and thus it is
                     * on the stack.
                     */
                    initialStack = StackAndThisManager(initialStack = treapListOf(CallStackItem(host, null)), thisInference = ThisInference.Explicit(host.contractId)),
                ) { subst ->
                    returnInfo[host]?.let { returnSummary ->
                        val toChange = returnSummary.entries.mapNotNull {
                            it.key `to?` (it.value as? CallGraphBuilder.CalledContract.SymbolicOutput)
                        }.filter {
                            it.second.which in subst
                        }
                        for((offset, called) in toChange) {
                            val remap = subst[called.which]?.get(called.offset)
                            if(remap == null) {
                                returnSummary.remove(offset)
                            } else {
                                returnSummary[offset] = remap
                            }
                        }
                    }
                }
                changed = resolved || changed
            }
        }
        return callgraph.mapValues {
            MethodNode(
                returnSummary = returnInfo[it.key],
                callNodes = it.value,
                abiInput = abiInfo[it.key]
            )
        }
    }

    private fun tacMethodToNode(m: ITACMethod): CallGraphMethodNode {
        val icore = m.code as CoreTACProgram
        val returnInfo = icore.code.flatMap { (node, cmds) ->
            cmds.mapIndexedNotNull { index, simple ->
                when (simple) {
                    is TACCmd.Simple.ReturnCmd -> {
                        LTACCmd(cmd = simple, ptr = CmdPointer(node, index)).narrow<TACCmd.Simple.ReturnCmd>()
                    }
                    is TACCmd.Simple.ReturnSymCmd -> {
                        LTACCmd(cmd = simple, ptr = CmdPointer(node, index)).narrow<TACCmd.Simple.ReturnSymCmd>()
                    }
                    else -> null
                }
            }
        }
        val callNodes = getCallNodes(icore)
        val contractReturnSummary = returnInfo.monadicMap {
            // get the return linking meta attached to each return cmd
            it.cmd.meta.find(TACMeta.RETURN_LINKING)
        }?.takeIf { it.isNotEmpty() }?.let { linking ->
            // what's the minimum size returned by this function (min over the lower bound of all returns)
            val minLowerBound = linking.map {
                it.returnSize
            }.minOrNull()!!
            // for each summary, filter out keys that are above this lower bound
            val filteredSummaries = linking.map {
                it.byOffset.filterKeys {
                    it + 0x20.toBigInteger() <= minLowerBound
                }
            }
            /*
               Find all keys that occur in all return locations
             */
            val allIndices = filteredSummaries.map {
                it.keys
            }.foldFirst { a, b -> a.intersect(b) }
            /* For each return site, get the called contract returned at each index in allIndices */
            filteredSummaries.flatMap { m ->
                allIndices.map {
                    it to m[it]
                }
            /*
               Group the called contracts by the return index
             */
            }.groupBy({ it.first }, { it.second }).entries.mapNotNull { (key, value) ->
                /*
                   if any contracts are null (no information at some return site for some index)
                   then fail for this index
                 */
                value.monadicMap {
                    it
                /*
                   is the same address returned at each index?
                 */
                }?.uniqueOrNull()?.let {
                    /*
                       Then summarize this function as returning address it at index key

                       To sum up: we know every return command returns a buffer of a size big enough to hold
                       key, each return command has information about the value returned at key, AND at each return
                       the value returned at index key is the same (possibly symbolic) address
                     */
                    key to it
                }
            }.toMap()

        }
        return CallGraphMethodNode(
            callNodes = callNodes,
            returnSummary = contractReturnSummary,
            abiInput = ABICallConvention.extractExpected(m)
        )
    }

    private fun getCallNodes(icore: CoreTACProgram): List<CallGraphNode.CallNode> {
        val callSummaries = icore.code.flatMap { (node, cmds) ->
            cmds.mapIndexedNotNull { index, simple ->
                (simple as? TACCmd.Simple.SummaryCmd)?.takeIf {
                    it.summ is CallSummary
                }?.let {
                    LTACCmd(cmd = simple, ptr = CmdPointer(node, index)).narrow<TACCmd.Simple.SummaryCmd>()
                }
            }
        }
        val stack = InlinedMethodCallStack(icore.analysisCache.graph)
        return callSummaries.map {
            (it.cmd.summ as CallSummary).let { summ ->
                CallGraphNode.CallNode(
                    id = summ.summaryId,
                    callTarget = summ.callTarget,
                    where = it.ptr,
                    sigHash = summ.sigResolution,
                    symbolicArgs = summ.callConvention.input.rangeToDecomposedArg.filterKeys {
                        it.to == (it.from + 31.toBigInteger())
                    }.mapNotNull { (r, v) ->
                        r.from `to?` v.contractReference
                    }.toMap(),
                    inSize = summ.inSize,
                    summary = summ,
                    abiArgs = summ.callConvention.input.encodedArguments,
                    stackSummary = stack.activationRecordsAt(it.ptr)
                )
            }
        }
    }
}

/**
 * An [analysis.icfg.InterContractCallResolver.AbstractNode] that contains a mixture of resolved/unresolved
 * calls
 */
typealias MethodNode = InterContractCallResolver.AbstractNode<InterContractCallResolver.CallGraphNode>
/**
 * An [analysis.icfg.InterContractCallResolver.AbstractNode] where all calls are unresolved.
 */
typealias CallGraphMethodNode = InterContractCallResolver.AbstractNode<InterContractCallResolver.CallGraphNode.CallNode>
