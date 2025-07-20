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

import algorithms.UnionFind
import analysis.CmdPointer
import analysis.commands
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysisResult
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.UniqueCache
import log.Logger
import log.LoggerTypes
import scene.IContractClass
import scene.ITACMethod
import spec.CVLKeywords
import spec.toVar
import tac.TACBasicMeta
import tac.Tag
import utils.*
import vc.data.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * A utility class for splitting.
 *
 * Containing shortcuts and common convenience functions, and keeping the calculated ternaries for all methods.
 */
class SplitContext(
    val contract: IContractClass,
    val base: StorageAnalysis.Base
) {
    val logger = Logger(LoggerTypes.STORAGE_SPLITTING)

    val methods = contract.getDeclaredMethods()

    val layout by lazy {
        StorageLayoutHelper(contract, showWarnings = !storageAnalysisFail)
    }

    /**
     * Indicates whether there was a storage analysis failure.
     */
    var storageAnalysisFail: Boolean = false

    /**
     * variables mentioned in annotations should not be split in any way. Much like keyword variables.
     */
    val mentionedVars by lazy {
        mutableMapOf<ITACMethod, Set<TACSymbol.Var>>()
            .also { map ->
                for (method in methods) {
                    val temp = mutableSetOf<TACSymbol.Var>()
                    map[method] = temp
                    val code = method.code as CoreTACProgram
                    temp += code.internalFuncExitVars
                }
            }
    }

    /**
     * If there is some storage access which is related to more than one [StorageAnalysisResult.NonIndexedPath], it
     * means we cannot split the accesses cleanly. In such a case we generate only one new storage variable to all
     * such paths. The hash expression used to access storage are kept, and the differentiation between accesses remains
     * there.
     */
    val pathEquivalence by lazy {
        UnionFind<NonIndexedPath>().also { uf ->
            for (method in methods) {
                for ((_, cmd) in storageCommands(method)) {
                    storagePaths(cmd)?.let { uf.union(it) }
                }
            }
        }.toImmutable()
    }

    fun representativePath(path: NonIndexedPath) = pathEquivalence.getRepresentative(path)
    fun representativePath(cmd: TACCmd.Simple) =
        storagePaths(cmd)?.firstOrNull()?.let {
            representativePath(it)
        }

    fun isForbiddenVar(v: TACSymbol.Var, method: ITACMethod) =
        isKeywordVar(v) || mentionedVars[method]?.contains(v) == true

    fun isStorage(v: TACSymbol.Var) =
        contract.instanceId == v.meta.find(base.storageKey)

    /** Since this is accessed a lot, we save it in a map instead of recalculating */
    private val storageCommands by lazy {
        methods.associateWith { method ->
            method.commands.filter { lcmd ->
                lcmd.cmd is TACCmd.Simple.StorageAccessCmd && lcmd.cmd.meta.containsKey(base.isStorageAccessKey)
            }
        }
    }

    fun storageCommands(method: ITACMethod) = storageCommands[method]!!

    /** a shared unique cache for created ternaries */
    private val ternaryCache: UniqueCache<Ternary> = UniqueCache()

    private val ternaries by lazy {
        methods.associateWith { method ->
            TernaryCalculator(
                method.code as CoreTACProgram,
                isForbiddenVar = { v -> isForbiddenVar(v, method) },
                ternaryCache = ternaryCache
            )
        }
    }

    /** is [v] queried at the rhs of [ptr1] the same as when it's queried at the rhs of [ptr2] */
    fun isEquivAtPtrs(method : ITACMethod, v: TACSymbol.Var, ptr1: CmdPointer, ptr2: CmdPointer): Boolean {
        if (ptr1 == ptr2) {
            return true
        }
        if (ptr1.block == ptr2.block) {
            val commands = (method.code as CoreTACProgram).code[ptr1.block]!!
            return (minOf(ptr1.pos, ptr2.pos)..<maxOf(ptr1.pos, ptr2.pos)).none {
                commands[it].getLhs() == v
            }
        }
        // so this is bit lazy, because it can give false answer when it should give true. But I say we should wait
        // till we see it happen before fixing (I'm not even sure we ever get here).
        return ternaries[method]!!.singleDef.let {
            val p1 = it.getDefinitiveDef(ptr1, v)
            val p2 = it.getDefinitiveDef(ptr2, v)
            p1 != null && p1 == p2
        }
    }

    fun isDefOf(method : ITACMethod, useSite: CmdPointer, assignSite: CmdPointer): Boolean {
        val assignLCmd = (method.code as CoreTACProgram).analysisCache.graph.elab(assignSite)
        val v = assignLCmd.cmd.getLhs() ?:
            error("Can't call isDefOf with a non assigning command $assignLCmd")
        return ternaries[method]!!.singleDef.getDefinitiveDef(useSite, v) == assignSite
    }

    fun storagePathsOrNull(cmd: TACCmd.Simple) =
        cmd.meta.find(TACMeta.STORAGE_PATHS)?.storagePaths

    fun storagePaths(cmd: TACCmd.Simple) =
        storagePathsOrNull(cmd) ?: if (!storageAnalysisFail) {
            error("Couldn't find storage paths")
        } else {
            null
        }

    fun ternaries(method: ITACMethod) = ternaries[method]!!

    /**
     * A storage access path can be rewritten into a simple variable (and not a mapping), if it's a root level
     * path, and there are no other paths that it is equivalent too. In such a case it must remain a mapping.
     */
    @OptIn(ExperimentalContracts::class)
    fun canRewriteAsVar(repPath: NonIndexedPath): Boolean {
        contract {
            returns(true) implies (repPath is NonIndexedPath.Root)
        }
        return canRewriteAsVar(repPath, pathEquivalence.getEquivalenceClass(repPath).size)
    }

    companion object {
        @OptIn(ExperimentalContracts::class)
        fun canRewriteAsVar(repPath: NonIndexedPath, equivClassSize: Int) : Boolean {
            contract {
                returns(true) implies (repPath is NonIndexedPath.Root)
            }
            return repPath is NonIndexedPath.Root && equivClassSize == 1
        }

        fun isSimpleVar(v: TACSymbol.Var) =
            v.tag is Tag.Bits || v.tag is Tag.Bool

        private val keywordMetas =
            setOf(
                TACMeta.EVM_MEMORY,
                TACBasicMeta.DECOMP_INPUTSCALAR_SORT,
                TACMeta.IS_CALLDATA,
                TACMeta.IS_RETURNDATA,
                TACMeta.SYM_RETURN,
            )

        private val keywordVars =
            (TACKeyword.entries.map { it.toVar() } +
                    CVLKeywords.entries.map { it.toVar() }).toSet()

        fun isKeywordVar(v: TACSymbol.Var) =
            v in keywordVars || keywordMetas.any { v.meta.containsKey(it) }

        val TACCmd.Simple.storageLoc
            get() =
                when (this) {
                    is TACCmd.Simple.AssigningCmd.WordLoad -> loc
                    is TACCmd.Simple.WordStore -> loc
                    else -> null
                }

    }
}
