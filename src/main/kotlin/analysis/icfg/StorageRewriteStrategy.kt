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

import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.EthereumVariables
import analysis.LTACCmd
import instrumentation.transformers.CodeRemapper
import scene.ContractId
import scene.ITACMethod
import scene.StorageCloning
import tac.CallId
import tac.NBId
import vc.data.*

/**
 * Describes how storage variables are rewritten as part of the preparation for inlining
 */
sealed interface StorageRewriteStrategy {
    /**
     * Assuming some call-site where the value of `this` is described by [thisAtCall], and where the call-type
     * is [callType], return a function that transforms an [ITACMethod] for inlining with this
     * [callType] by rewriting the storage references within that method.
     */
    fun getStorageRewriter(thisAtCall: ContractId, callType: TACCallType): (ITACMethod) -> CoreTACProgram

    /**
     * Use for direct calls only: doesn't touch storage
     */
    object IdentityStrategy : StorageRewriteStrategy {
        override fun getStorageRewriter(thisAtCall: ContractId, callType: TACCallType): (ITACMethod) -> CoreTACProgram {
            return { m: ITACMethod ->
                m.code as CoreTACProgram
            }
        }
    }

    /**
     * Should be protected, intended for internal use
     */
    fun getRemapper(thisAtCall: ContractId, variableMapper: (contract: ContractId, v: TACSymbol.Var) -> TACSymbol.Var) : CodeRemapper<Unit> {
        return CodeRemapper(
            blockRemapper = { nbId: NBId, _: (CallId) -> CallId, _: CodeRemapper.BlockRemappingStrategy.RemappingContext, _: Unit ->
                nbId
            },
            idRemapper = { _ ->
                { _: Any, id: Int, _: () -> Int ->
                    id
                }
            },
            callIndexStrategy = { _, callIndex, _ ->
                callIndex
            },
            variableMapper = CodeRemapper.VariableRemapper { _, v ->
                if(TACMeta.STORAGE_KEY !in v.meta) {
                    return@VariableRemapper v
                }
                val key = v.meta[TACMeta.STORAGE_KEY]!!
                if(key == thisAtCall) {
                    return@VariableRemapper v
                }
                return@VariableRemapper variableMapper(key, v)
            }
        )
    }

    /**
     * A "basic" strategy where the only expected storage references are to the callee contract and or "this",
     * and storage is unsplit; this is the case at the initial inlining of delegates.
     */
    object BasicDelegateStrategy : StorageRewriteStrategy {
        override fun getStorageRewriter(thisAtCall: ContractId, callType: TACCallType): (ITACMethod) -> CoreTACProgram {
            check(callType == TACCallType.DELEGATE) {
                "Unexpected call type for early delegate inlining: $callType"
            }
            return { m: ITACMethod ->
                val mapper = getRemapper(thisAtCall) { key, v ->
                    check(m.getContainingContract().instanceId == key) {
                        "Expected to find reference to host contract ID: ${m.getContainingContract().instanceId}, found $key instead (rewriting to this == $thisAtCall"
                    }
                    check(v.meta.get(TACMeta.SCALARIZATION_SORT) == ScalarizationSort.UnscalarizedBuffer) {
                        "Unexpected scalarization sort for delegate inlining time, have ${v.meta.get(TACMeta.SCALARIZATION_SORT)} for $v when rewriting $m with this = $thisAtCall"
                    }
                    EthereumVariables.getStorageInternal(thisAtCall).withMeta {
                        it + (TACMeta.SCALARIZATION_SORT to ScalarizationSort.UnscalarizedBuffer)
                    }
                }
                val theCode = m.code
                (theCode as CoreTACProgram).remap(mapper, Unit).let { (newCode, newGraph, newProc) ->
                    check(newProc == theCode.procedures) {
                        "Remapping for storage should not have changed the procedures, and yet: $newProc vs ${theCode.procedures}"
                    }
                    check(newGraph == theCode.blockgraph) {
                        "Remapping for storage should have changed the block graph, and yet: $newGraph vs ${theCode.blockgraph}"
                    }
                    theCode.copy(code = newCode)
                }
            }
        }
    }

    /**
     * A much more complicated rewriter, designed for handling delegate inlinings post storage analysis.
     * [equivClassGetter] returns, for any [ContractId] c, the set of [ContractId] with which c's storage has been
     * merged (as can acculumate during summarization inlining, see [InliningDecisionManager.PostStorageAnalysis]).
     */
    class ContextSensitiveRewrite(val equivClassGetter: (ContractId) -> Set<ContractId>) : StorageRewriteStrategy {
        /**
         * If the [callType] is not a delegate call [TACCallType.DELEGATE], then this function behaves like
         * [StorageRewriteStrategy.IdentityStrategy]. Otherwise, it finds within the body of `m` references to storage
         * which should be translated to refer to the storage of [thisAtCall]. See [rewriteDelegate] documentation for details.
         */
        override fun getStorageRewriter(thisAtCall: ContractId, callType: TACCallType): (ITACMethod) -> CoreTACProgram {
            if(callType != TACCallType.DELEGATE) {
                return { m: ITACMethod -> m.code as CoreTACProgram }
            }
            return { m: ITACMethod ->
                rewriteDelegate(thisAtCall, m)
            }
        }

        /**
         * Traverse the body of [m], and find all storage accesses that are reachable
         * only via an unbroken chain of delegate calls (the "delegate call" included for the call into [m] is implicitly
         * included). If this is the case, then that storage access within some inlined method `B.g` must be a reference
         * to the storage `this` within [m]. However, because we are delegatecalling into [m] these storage references
         * must be rewritten to reference [thisAtCall] instead.
         */
        private fun rewriteDelegate(thisAtCall: ContractId, m: ITACMethod) : CoreTACProgram {
            val code = m.code as CoreTACProgram
            val stack = InlinedMethodCallStack(code.analysisCache.graph)
            val equivClass = equivClassGetter(thisAtCall)
            val newVars = mutableSetOf<TACSymbol.Var>()
            val remapper = getRemapper(thisAtCall) { srcId, v ->
                check(srcId in equivClass) {
                    "Want to merge in $srcId into storage for $thisAtCall, but it's not in our equivClass $equivClass"
                }
                StorageCloning.cloneStorageVarTo(
                    sourceId = srcId,
                    storageVar = v,
                    targetContract = thisAtCall
                ).also {
                    newVars.add(it)
                }
            }.commandMapper(Unit)
            val rewriter = object : TACCmdMapperWithPointer {
                override var currentPtr: CmdPointer? = null
                override val decls: MutableSet<TACSymbol.Var> = newVars

                override fun mapCommand(c: LTACCmd): List<TACCmd.Simple> {
                    /**
                     * If we find any access which has a non-delegate call along the call chain, then
                     * the storage being accessed here isn't part of [thisAtCall]
                     */
                    if(stack.activationRecordsAt(c.ptr).any {
                        it.callType != TACCallType.DELEGATE
                    }) {
                        return listOf(c.cmd)
                    }
                    // don't rewrite dynamic storage commands, they are manipulating the right storage
                    if(TACMeta.DYNAMIC_STORAGE_MANAGEMENT in c.cmd.meta) {
                        return listOf(c.cmd)
                    }
                    return listOf(remapper(c.cmd))
                }
            }
            return rewriter.mapCommands(code)
        }
    }
}
