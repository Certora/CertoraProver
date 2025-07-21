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

package analysis.split

import analysis.LTACCmd
import analysis.icfg.Inliner
import analysis.snarrowOrNull
import analysis.split.annotation.StorageSnippetInserter
import analysis.split.arrays.PackedArrayRewriter
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysisResult
import datastructures.stdcollections.*
import log.*
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.*
import statistics.ANALYSIS_SUCCESS_STATS_KEY
import statistics.recordSuccess
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors


/**
 * Calculates how to split storage vars (but may also split normal vars) into a few vars in order to save in packing and
 * unpacking operations. See the Storage Splitting design document [https://www.notion.so/certora/Storage-Splitting-2-1-432dd60ee7ee44dcadd3f2a158972dd6]
 * for high level details.
 */
class StorageSplitter(val contract: IContractClass, val base: StorageAnalysis.Base) {

    /**
     * Entry point.
     * Returns true if anything was done (even if only annotations).
     */
    private fun splitStorage() : Boolean {

        when(base) {
            StorageAnalysis.Base.STORAGE ->
                if (contract !is IMutableStorageInfo) {
                    return false
                }
            StorageAnalysis.Base.TRANSIENT_STORAGE ->
                if (contract !is IMutableTransientStorageInfo) {
                    return false
                }
        }

        if((contract as? IContractWithSource)?.src?.isLibrary == true) {
            Logger(LoggerTypes.STORAGE_SPLITTING).info {
                "${contract.name} is a library, so we don't run storage splitting on it."
            }
            return false
        }

        var storageAddress: BigInteger? = null

        fun recordFailure(m: ITACMethod, lcmd: LTACCmd?) {
            recordSuccess(
                false,
                "0x${contract.instanceId.toString(16)}",
                m.toRef().toString(),
                ANALYSIS_SUCCESS_STATS_KEY,
                base.statisticsKey
            )

            val graph = (m.code as CoreTACProgram).analysisCache.graph
            val rangeWithMsgDetails = lcmd?.let { getSourceHintWithRange(it, graph, m) } ?: FailureInfo.NoFailureInfo

            CVTAlertReporter.reportAlert(
                type = CVTAlertType.STORAGE_SPLITTING,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = rangeWithMsgDetails.range,
                message = "Storage splitting for $base failed in contract ${m.getContainingContract().name}, " +
                    "function ${m.soliditySignature ?: m.name}. ${rangeWithMsgDetails.additionalUserFacingMessage} This might have an impact on running times",
                url = CheckedUrl.ANALYSIS_OF_STORAGE,
            )
        }

        val cx = SplitContext(contract, base)


        for (method in cx.methods) {
            if (method.code !is CoreTACProgram) {
                return false
            }

            fun recordFailure(lcmd: LTACCmd?, log: () -> String) {
                recordFailure(method, lcmd)
                cx.logger.warn(log)
            }

            fun recordFailure(log: () -> String) = recordFailure(null, log)

            val entries = cx.storageCommands(method)
            if (entries.isEmpty()) {
                continue
            }
            val storageVarSet = entries.asIterable().mapToSet {
                when(it.cmd) {
                    is TACCmd.Simple.WordStore -> it.cmd.base
                    is TACCmd.Simple.AssigningCmd.WordLoad -> it.cmd.base
                    else -> `impossible!`
                }
            }
            val storageVar = storageVarSet.singleOrNull() ?: run {
                recordFailure {
                    "Multiple storage variables (${storageVarSet}) found in method $method of $contract"
                }
                return false
            }

            val address =
                storageVar.meta.find(base.storageKey) ?: run {
                    recordFailure {
                        "Didn't find address on variable $storageVar in $method of contract $contract"
                    }
                    return false
                }

            if (storageAddress == null) {
                storageAddress = address
            } else if (storageAddress != address) {
                recordFailure {
                    "Inconsistent address in storage: $storageAddress vs $address for $storageVar in $method of $contract"
                }
                return false
            }

            entries.firstOrNull { cx.storagePathsOrNull(it.cmd) == null }?.let {
                recordFailure(it) {
                    "In contract $contract, method $method, $it (and possibly more commands) have no storage path " +
                        "associated with them (this prevents storage splitting being applied on this contract)."
                }
                // fallback to a mode, where the [SplitRewriter]'s algorithm will be used, but only for trying and generating
                // [DisplayPath]s for the [CallTrace].
                cx.storageAnalysisFail = true
            }
        }

        val contractAddress = storageAddress ?: return false
        check(contractAddress == contract.instanceId) { "Conflicting addresses: $contractAddress and ${contract.instanceId}" }

        cx.logger.debug {
            "\nCONTRACT = ${contract.name}\n"
        }

        val arrayRewriter = PackedArrayRewriter(cx)
        val badArrays = mutableSetOf<StorageAnalysisResult.NonIndexedPath>()

        fun loopFinderUntilSuccess(): SplitFinder.Result.SUCCESS {
            while (true) {
                when (val res = SplitFinder(cx, arrayRewriter.forFinder(badArrays)).calc()) {
                    is SplitFinder.Result.SUCCESS ->
                        return res

                    is SplitFinder.Result.FAILURE ->
                        badArrays += res.badArrays
                }
            }
        }

        val (storageSplits, varSplits) = loopFinderUntilSuccess()
        val rewriterInfo = arrayRewriter.forRewriter(badArrays)

        val newStorageVars = mutableSetOf<TACSymbol.Var>()

        for (method in cx.methods) {

            val (patcher, changes, newVars) = SplitRewriter(
                cx,
                method,
                varSplits[method]!!,
                storageSplits,
                rewriterInfo
            ).rewrite()

            if (!cx.storageAnalysisFail) {
                newStorageVars += newVars
                SplitDebugger(cx, method, varSplits[method]!!, changes).log()
            }

            method.updateCode(patcher)
        }

        if (!cx.storageAnalysisFail) {
            when(base) {
                StorageAnalysis.Base.STORAGE -> {
                    check(cx.contract is IMutableStorageInfo) {
                        "Expected the contract ${cx.contract.name} to be [IMutableStorageInfo], " +
                            "but it is actually ${cx.contract::class}"
                    }
                    cx.contract.setStorageInfo(
                        StorageInfoWithReadTracker((cx.contract.storage as StorageInfoWithReadTracker).storageVariables + newStorageVars.map {
                                it to null
                        })
                    )
                }
                StorageAnalysis.Base.TRANSIENT_STORAGE -> {
                    check(cx.contract is IMutableTransientStorageInfo) {
                        "Expected the contract ${cx.contract.name} to be [IMutableTransientStorageInfo], " +
                            "but it is actually ${cx.contract::class}"
                    }
                    cx.contract.setTransientStorageInfo(
                        StorageInfo((cx.contract.transientStorage as StorageInfo).storageVariables + newStorageVars)
                    )
                }
            }

            arrayRewriter.finalLog(badArrays)
            // Currently we record success anyway... not sure this is what we want to see.
            recordSuccess("0x${contract.instanceId.toString(16)}", "ALL", base.statisticsKey, true)
        }
        return true
    }

    companion object {
        fun splitStorage(contract: IContractClass) {
            val processedBases = StorageAnalysis.Base.entries.filter {
                StorageSplitter(contract, it).splitStorage()
            }
            if (processedBases.isNotEmpty()) {
                markUnresolvedDelegateCalls(contract)
                StorageSnippetInserter.Companion.addSnippetsToRemainingAccesses(contract, processedBases.toSet())
            }
        }

        /**
         * Here we mark any remaining delegate calls as being illegal to inline to a "real" contract method from
         * contracts other than `contract`. This is conservative: the splitting/unpacking done here is sound only
         * because we assume that we have seen all possible storage accesses, and further, that we have validated
         * that the splitting/unpacking is sound to do in the presence of all of those accesses. If later inlining
         * introduces code that is incompatible with these assumptions, then we will be unsound.
         *
         * NB: checking that the inlined callee respects the splitting decisions made here would be one way to do it,
         * but this is hard.
         */
        private fun markUnresolvedDelegateCalls(contract: IContractClass) {
            for (method in contract.getDeclaredMethods()) {
                val code = method.code as CoreTACProgram
                val patcher = code.toPatchingProgram()
                val unresolvedDelegateCalls = code.parallelLtacStream().filter {
                    it.snarrowOrNull<CallSummary>()?.origCallcore?.callType == TACCallType.DELEGATE
                }.collect(Collectors.toList())
                unresolvedDelegateCalls?.let { toAnnot ->
                    for (lc in toAnnot) {
                        patcher.update(lc.ptr) {
                            check(it is TACCmd.Simple.SummaryCmd &&
                                it.summ is CallSummary &&
                                it.summ.callType == TACCallType.DELEGATE) {
                                "At $lc, asked to invalidate a delegate call, but it isn't the right command"
                            }
                            it.copy(
                                summ = it.summ.copy(
                                    cannotBeInlined = Inliner.IllegalInliningReason.DELEGATE_CALL_POST_STORAGE
                                )
                            )
                        }
                    }
                }
                method.updateCode(patcher)
            }
        }
    }

}

