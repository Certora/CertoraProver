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

package analysis.icfg

import analysis.maybeAnnotation
import datastructures.stdcollections.*
import analysis.storage.StorageAnalysisResult
import config.Config
import log.*
import scene.*
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

private val logger = Logger(LoggerTypes.INLINER)

interface InliningDecisionManager {
        /**
     * Returns a [StatelessDecisionManager] which can be used to quickly prune out nodes that ineligible for inlining
     * (e.g., due to having a summary, or being a delegate call when we're trying to do direct inlining).
     */
    fun getStatelessManager(): StatelessDecisionManager

    /**
     * Returns a [StorageRewriteStrategy] which describes how to translate storage references for methods that have
     * been selected for inlining.
     */
    fun getStorageRewriteStrategy(): StorageRewriteStrategy

    /**
     * Given information about [thisAtCall], the summary [summ], and the concrete [callee] selected, determine
     * if inlining should be done by returning true (should inline) or false (do not inline). All subclasses
     * of [SimpleDecisionManager] simply delegate to the result of their respected [StatelessDecisionManager]
     */
    fun shouldInline(
        thisAtCall: ContractId,
        summ: CallSummary,
        callee: TACMethod
    ) : Boolean

    /**
     * A decision manager that serves as a "prepass" to determine which call sites can be inlined
     * without regard to the specific callee.
     */
    interface StatelessDecisionManager {
        fun eligibleForInlining(summ: CallSummary, callee: ContractId) : Boolean
    }

    /**
     * An [InliningDecisionManager] that does not consider the concrete callee nor track state.
     */
    sealed interface SimpleDecisionManager : InliningDecisionManager, StatelessDecisionManager {
        override fun update(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod) { }
        override fun shouldInline(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod): Boolean {
            return eligibleForInlining(summ, callee.getContainingContract().instanceId)
        }

        override fun getStatelessManager(): StatelessDecisionManager {
            return this
        }

        override fun eligibleForInlining(summ: CallSummary, callee: ContractId) : Boolean
    }

    /**
     * Called to indicate that [callee] was selected for inlining. It is guaranteed that this function
     * will always be called after [shouldInline] returns true and *before* any further calls
     * to [shouldInline]. Put another way, if a function is selected for inlining, then it
     * is guaranteed [update] will be called immediately after the invocation
     * of [shouldInline] which returned true.
     */
    fun update(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod)

    data object DelegatesOnly : SimpleDecisionManager {
        override fun eligibleForInlining(summ: CallSummary, callee: ContractId): Boolean {
            return summ.callType == TACCallType.DELEGATE
        }

        override fun getStorageRewriteStrategy(): StorageRewriteStrategy {
            return StorageRewriteStrategy.BasicDelegateStrategy
        }
    }

    data object Direct : SimpleDecisionManager {
        override fun eligibleForInlining(summ: CallSummary, callee: ContractId): Boolean {
            return summ.callType != TACCallType.DELEGATE
        }

        override fun getStorageRewriteStrategy(): StorageRewriteStrategy {
            return StorageRewriteStrategy.IdentityStrategy
        }
    }

    /**
     * Oh boy, hold on to your butts. Given a compiled rule [code], and [scene], determine which delegatecalls
     * are safe for inlining based on contracts touching disjoint sets of storage. This is done *quite* coarse grained,
     * but could (in principle) be revised to be even more precise, see [shouldInline] for details.
     */
    class PostStorageAnalysis(code: CoreTACProgram, private val scene: IScene) : InliningDecisionManager {
        /**
         * Records that the storage of [baseId] has been merged with those in [mergedContracts]. If [baseId] has been
         * merged with a contract with id `k`, then the extended storage variables added to [baseId]'s storage
         * are recorded in `mergedContracts[k]`
         */
        private class StorageState(
            val baseId: BigInteger,
            val mergedContracts: MutableMap<BigInteger, Set<TACSymbol.Var>>
        )

        /**
         * Records for each contract, the storage state of that contract. NB that for some k which maps to some [StorageState],
         * v, v's [StorageState.baseId] will be k (it is convenient to have a back reference).
         */
        private val effectiveState = mutableMapOf<BigInteger, StorageState>()

        /**
         * True if somehow there have been non-library delegate calls inlined, but we don't have a record
         * in [CoreTACProgram.stateExtensions].
         */
        private val unmanagedState : Boolean

        init {
            val currState = code.stateExtensions
            scene.getContracts().forEach {
                effectiveState[it.instanceId] = StorageState(it.instanceId, mutableMapOf())
            }
            val hasNonLibraryDelegateInlining = code.parallelLtacStream().mapNotNull {
                it `to?` it.maybeAnnotation(Inliner.CallStack.STACK_PUSH)
            }.filter { (_, push) ->
                push.convention != Inliner.CallConventionType.FromCVL && push.summary?.callType == TACCallType.DELEGATE
            }.filter { (_, push) ->
                /**
                 * This may at first seem pretty unsound, as we have seen more libraries which have their own namespace storage.
                 * However, we expect that any actual library invocations are easily statically resolvable, and so we'll have already incorporated
                 * that "library state" into the callee contract.
                 */
                push.callee.resolveIn(scene)?.evmExternalMethodInfo?.isLibrary != true
            }.anyMatch { true }
            unmanagedState = hasNonLibraryDelegateInlining && currState.instanceToExtendedVars.isEmpty()
            for((id, m) in currState.instanceToExtendedVars) {
                if(id !in effectiveState) {
                    throw IllegalStateException("In ${code.name}, have contract $id that doesn't exist in the scene?")
                }
                for((tgt, extendedVars) in m) {
                    /*
                     * Note that we assume that any delegate inlining decisions made previously were "correct"
                     */
                    effectiveState[id]!!.mergedContracts[tgt] = extendedVars
                }
            }
        }

        /**
         * For a contract address k, find the "root slots" in storage that are accessed anywhere in k, or null
         * if no such root slots were found. This is a cache of [projectSplitStorage]
         */
        private val projectionCache = ConcurrentHashMap<BigInteger, Set<BigInteger>?>()

        /**
         * For an [IContractClass], find all root slots included in the contract's storage,
         * returning null if some principle set can't be inferred (i.e., splitting of storage failed)
         */
        private fun IContractClass.projectSplitStorage(): Set<BigInteger>? {
            val store = this.storage
            val storageVars = if(store is StorageInfo) {
                store.storageVariables
            } else if(store is StorageInfoWithReadTracker) {
                store.storageVariables.keys
            } else {
                logger.warn {
                    "Unexpected storage variable for contract $this"
                }
                return null
            }
            if(storageVars.size == 1) {
                val singleton = storageVars.single()
                val sort = singleton.meta.find(TACMeta.STABLE_STORAGE_PATH)
                /**
                 * This is likely to be the "bare" `tacS!....` variable. This usually an indication that splitting
                 * failed (in which case we have to return null) OR storage isn't actually used. Let's confirm the latter case
                 * and just return the empty set if so.
                 */
                if(sort == null) {
                    logger.info {
                        "Found singleton storage for $this $singleton"
                    }
                    /*
                     one last check, do we perhaps never use storage in this contract at all?
                     */
                    if(this.getStandardMethods().all {
                        (it.code as CoreTACProgram).parallelLtacStream().noneMatch {
                            (it.cmd.getFreeVarsOfRhs().any {
                                it == singleton
                            } || it.cmd.getModifiedVar() == singleton) && TACMeta.REVERT_MANAGEMENT !in it.cmd.meta
                        }
                    }) {
                        logger.info {
                            "But apparently we don't use $singleton at all in $this, allowing"
                        }
                        return setOf()
                    }
                    /**
                     * Otherwise, this is very likely unsplit storage, so return null
                     */
                    return null
                }
            }
            val (unsplit, others) = storageVars.partition {
                it.isUnsplitStorage()
            }
            if(unsplit.size > 1) {
                logger.warn {
                    "Extremely suspcious: multiple unscalarized buffers reported for $this: $unsplit"
                }
                return null
            }
            /**
             * Project out the root slots of all stable storage families found.
             */
            return others.monadicMap { storageVar ->
                storageVar.meta.find(TACMeta.STABLE_STORAGE_FAMILY)?.let { family ->
                    family.storagePaths.map { path ->
                        path.rootSlot()
                    }
                }
            }?.flatMapToSet { it }
        }

        private fun StorageAnalysisResult.NonIndexedPath.rootSlot(): BigInteger = when(this) {
            is StorageAnalysisResult.NonIndexedPath.ArrayAccess -> this.base.rootSlot()
            is StorageAnalysisResult.NonIndexedPath.MapAccess -> this.base.rootSlot()
            is StorageAnalysisResult.NonIndexedPath.Root -> this.slot
            is StorageAnalysisResult.NonIndexedPath.StaticArrayAccess -> this.base.rootSlot()
            is StorageAnalysisResult.NonIndexedPath.StructAccess -> this.base.rootSlot()
        }

        private fun BigInteger.projectSplitStorage() = projectionCache.computeIfAbsent(this) {
            scene.getContract(it).projectSplitStorage()
        }

        /**
         * For a [StorageState] (which, remember, determines the effective storage slots used by [StorageState.baseId])
         * compute the root slots used.
         */
        private fun StorageState.projectRoots() : Set<BigInteger>? {
            /**
             * It is expected that these sets are disjoint (otherwise they shouldn't have been merged), but we double
             * check here
             */
            return (mergedContracts.keys + this.baseId).monadicMap {
                it.projectSplitStorage()
            }?.monadicFold { a, b ->
                if(a containsAny b) {
                    logger.warn {
                        "Incoherent status! have $a and $b merged under ${this.baseId} with ${this.mergedContracts}"
                    }
                    return@monadicFold null
                }
                a union b
            }
        }

        override fun getStatelessManager(): StatelessDecisionManager {
            return object : StatelessDecisionManager {
                override fun eligibleForInlining(summ: CallSummary, callee: ContractId): Boolean {
                    return true
                }

            }
        }

        override fun getStorageRewriteStrategy(): StorageRewriteStrategy {
            return StorageRewriteStrategy.ContextSensitiveRewrite {
                effectiveState[it]?.mergedContracts?.keys.orEmpty() + it
            }
        }

        override fun shouldInline(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod): Boolean {
            if(summ.callType != TACCallType.DELEGATE) {
                return true
            }
            /**
             * Allow delegatecalls to `this`, that's always safe
             */
            if(thisAtCall == callee.getContainingContract().instanceId) {
                return true
            }
            if(unmanagedState) {
                return false
            }
            /*
              If there is a *chance* this is a library, we have to bail out here. no way: we don't run any of
              the analyses on libraries, so we can't inline its body
             */
            if(callee.evmExternalMethodInfo?.isLibrary != false) {
                return false
            }
            // otherwise, check that these contracts access disjoint sets of state
            val st = effectiveState[thisAtCall]
                ?: throw IllegalStateException("Inliner is asking about contract we've never heard of $thisAtCall")
            val calleeContract = callee.getContainingContract().instanceId
            if(calleeContract in st.mergedContracts) {
                return true
            }
            val effectiveRoots = st.projectRoots() ?: return false
            val toCallRoots = calleeContract.projectSplitStorage() ?: return false
            if(toCallRoots.isNotEmpty() && Config.AssumeDeadStorageIsZero.get()) {
                return false
            }
            return if(!effectiveRoots.containsAny(toCallRoots)) {
                Logger.regression {
                    "Allowing delegation from ${scene.getContract(thisAtCall).name} into ${callee.getContainingContract().name}"
                }
                true
            } else {
                logger.debug {
                    "Deciding to not inline $callee for delegatecall from $thisAtCall, they access shared roots\n >> effective state of $thisAtCall:" +
                        " $effectiveRoots\n >> callee roots $toCallRoots\n >> intersection: ${effectiveRoots.intersect(toCallRoots)}"
                }
                Logger.regression {
                    "Refusing to allow delegation from ${scene.getContract(thisAtCall).name} into ${callee.getContainingContract().name}"
                }
                false
            }
        }

        private fun TACSymbol.Var.isUnsplitStorage() = this.meta.find(TACMeta.STABLE_STORAGE_PATH) == null

        override fun update(thisAtCall: ContractId, summ: CallSummary, callee: TACMethod) {
            if(summ.callType != TACCallType.DELEGATE) {
                return
            }
            /**
             * Note that we are **not** doing union find here, the unioning is very much "directional".
             */
            val st = effectiveState[thisAtCall]
                ?: throw IllegalStateException("Inliner is asking about contract we've never heard of $thisAtCall")
            val toAdd = callee.getContainingContract().instanceId
            if(toAdd in st.mergedContracts) {
                return
            }
            val stateVars = callee.getContainingContract().storage.stateVars()
            st.mergedContracts[toAdd] = stateVars.mapNotNullToSet {
                if(it.isUnsplitStorage()) {
                    return@mapNotNullToSet null
                }
                StorageCloning.cloneStorageVarTo(
                    sourceId = toAdd, targetContract = thisAtCall, storageVar = it
                )
            }
        }

        fun getExtendedState(): Map<BigInteger, Map<BigInteger, Set<TACSymbol.Var>>> {
            return effectiveState.mapValues { it.value.mergedContracts }
        }

    }
}
