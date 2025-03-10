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

import analysis.CmdPointer
import analysis.TACCommandGraph
import analysis.storage.LoadInfo
import analysis.storage.LoadInfo.Companion.loadInfoOrNull
import analysis.storage.StoreInfo
import analysis.storage.StoreInfo.Companion.storeInfoOrNull
import analysis.storage.WidthConstraints
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import log.*
import scene.IContractClass
import scene.IMutableStorageInfo
import scene.ITACMethod
import spec.cvlast.typedescriptors.VMSignedNumericValueTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACMeta.SIGN_EXTENDED_STORE
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors

/**
 * Following splitting packed fields into separate variables, this constrains the values of loads out of storage to
 * be in their type's bounds. So a value packed into 16 bits needs now an added assume that it is between 0 - 32.  This
 * is simple enough, except that for signed value (that are not 256 bits wide) we get the following pattern:
 *
 *    x is some sign extend value starting at bit b
 *    y := x & 0xffff  // the mask is b least significant 1's
 *    store y in some place (non-indexed-path) in storage
 *    ...
 *    z := load from the place
 *    t := signextend z from bit b.
 *    now freely use t
 *
 * If this is consistent everywhere for this non-indexed-path, then we can remove the masking operation for `y` and the
 * sign-extend operation for `t`. In essence, instead of storing the masked version, we store the sign-extended version.
 *
 * As these patterns always appear within the same block, the check and rewrite are straightforward. The only
 * exception to this pattern is when a constant is stored, in which case the masking may be pre-calculated. In such
 * a case we replace the constant with a sign-extended version.
 *
 * In reality, the fact that `x` is sign-extended is never really checked. Instead, since we know all load sites
 * immediately sign-extend, we just replace the masking operation with a sign-extend. Most of the time (if not all),
 * this will result in two consecutive sign-extensions from the same bit. Later optimizations down the pipeline will
 * remove at least one of these.
 *
 * If any one of the checks here fails, we drop the whole optimization and give out a warning. This is not ideal, since
 * we could have still rewritten any non-indexed-path that adheres to the pattern. But we expect this to never happen.
 */
class StorageTypeBounder(private val contract: IContractClass) {

    companion object {

        /** Returns the operand followed by the mask, or null if its not masking with a constant */
        private fun TACExpr.asMaskOrNull() =
            if (this is TACExpr.BinOp.BWAnd) {
                when {
                    o1 is TACExpr.Sym.Const && o2 is TACExpr.Sym.Var -> o2 to o1.s.value
                    o2 is TACExpr.Sym.Const && o1 is TACExpr.Sym.Var -> o1 to o2.s.value
                    else -> null
                }
            } else {
                null
            }

        fun TACCommandGraph.intraBlockDef(v: TACSymbol.Var, ptr: CmdPointer): CmdPointer? {
            val defPtr = findLastUntil(ptr) { it.getLhs() == v }
                ?: return null
            val defCmd = (elab(defPtr).cmd as? AssignExpCmd)
                ?: return defPtr
            return when (defCmd.rhs) {
                is TACExpr.Sym.Var -> intraBlockDef(defCmd.rhs.s, defPtr)
                else -> defPtr
            }
        }

        /** returns the non-trivial use site if it is the block, with all the intermediate vars used */
        fun TACCommandGraph.intraBlockUse(v: TACSymbol.Var, ptr: CmdPointer): Pair<CmdPointer, List<TACSymbol.Var>>? {
            val (usePtr, useCmd) = iterateBlock(ptr)
                .firstOrNull {
                    it.cmd !is TACCmd.Simple.AnnotationCmd && v in it.cmd.getFreeVarsOfRhs()
                }
                ?: return null
            return if ((useCmd as? AssignExpCmd)?.rhs == v.asSym()) {
                intraBlockUse(useCmd.lhs, usePtr)?.let { (resPtr, usedVars) ->
                    resPtr to (usedVars + v)
                }
            } else {
                usePtr to listOf(v)
            }
        }

    }

    private class AnalysisFailedException(msg: String) : Exception(msg)

    /** entry point */
    fun addBounds() {
        if (contract is IMutableStorageInfo) {
            val exception = AtomicReference<AnalysisFailedException?>()
            val workers =
                contract.getDeclaredMethods().parallelStream().mapNotNull {
                    try {
                        MethodWorker(it).also { worker ->
                            worker.prepare()
                        }
                    } catch (e: AnalysisFailedException) {
                        exception.set(e)
                        null
                    }
                }.collect(Collectors.toList())

            exception.get()?.let {
                Logger.alwaysWarn(it.message!!)
                fallBackRewrite()
                return
            }

            workers.forEach {
                it.rewrite()
            }
        }
    }


    private inner class MethodWorker(val method: ITACMethod) {
        private val code = method.code as CoreTACProgram
        private val graph = code.analysisCache.graph
        private val patcher = ConcurrentPatchingProgram(code)

        private val singleUseVars = run {
            val usedVars = mutableSetOf<TACSymbol.Var>()
            val usedVarsWithMoreThanOneUseSite = mutableSetOf<TACSymbol.Var>()
            graph.commands
                .filter { (_, cmd) -> cmd !is TACCmd.Simple.AnnotationCmd }
                .map { (_, cmd) -> cmd.getFreeVarsOfRhs() }
                .forEach { vs ->
                    for (v in vs) {
                        if (!usedVars.add(v)) {
                            usedVarsWithMoreThanOneUseSite += v
                        }
                    }
                }
            usedVars - usedVarsWithMoreThanOneUseSite
        }

        private fun isSmallSigned(type: VMTypeDescriptor) =
            type is VMSignedNumericValueTypeDescriptor && type.bitwidth < EVM_BITWIDTH256

        fun prepare() {
            graph.commands.parallelStream().forEach { lcmd ->
                loadInfoOrNull(lcmd)?.let { info ->
                    val type = info.type
                    if (type != null && isSmallSigned(type)) {
                        if (!rewriteLoad(info)) {
                            throw AnalysisFailedException(
                                "Type Bounding failed for load of ${info.base} of type $type " +
                                    "at ${info.lcmd.ptr}"
                            )
                        }
                        patcher.insertAfter(lcmd.ptr, WidthConstraints(info.lhs).signed(info.width))
                    } else if (info.width < EVM_BITWIDTH256) {
                        patcher.insertAfter(lcmd.ptr, WidthConstraints(info.lhs).unsigned(info.width))
                    }
                }
                storeInfoOrNull(lcmd)?.let { info ->
                    info.type?.takeIf(::isSmallSigned)?.let {
                        if (!rewriteStore(info)) {
                            throw AnalysisFailedException(
                                "Type Bounding failed for store of ${info.base} of type ${info.type} " +
                                    "at ${info.lcmd.ptr}"
                            )
                        }
                    }
                }
            }

        }

        private fun rewriteLoad(info: LoadInfo): Boolean {
            val (usePtr, intermediateVars) = graph.intraBlockUse(info.lhs, info.lcmd.ptr) ?: return false
            if (!singleUseVars.containsAll(intermediateVars)) {
                return false
            }
            val useLCmd = graph.elab(usePtr)
            val useCmd = useLCmd.cmd
            if (useCmd is AssignExpCmd &&
                useCmd.rhs is TACExpr.BinOp.SignExtend &&
                useCmd.rhs.o2 is TACExpr.Sym.Var &&
                useCmd.rhs.o1.getAsConst() == (info.width / 8 - 1).toBigInteger()
            ) {
                patcher.replace(usePtr, useCmd.copy(rhs = useCmd.rhs.o2))
                return true
            }
            // A load of a signed variable may be directly written back to storage of the same type.
            storeInfoOrNull(useLCmd)?.let { storeInfo ->
                if (storeInfo.type == info.type) {
                    return true
                }
            }
            return false
        }

        private fun rewriteStore(info: StoreInfo): Boolean {
            val (ptr, cmd) = info.lcmd

            fun TACSymbol.Const.signExtended() =
                EVMOps.signExtendFromBit(this.value, info.width).asTACExpr

            when (val value = info.value) {
                is TACSymbol.Const -> {
                    patcher.replace(
                        ptr,
                        when (cmd) {
                            is AssignExpCmd -> cmd.copy(rhs = value.signExtended())
                            is TACCmd.Simple.WordStore -> cmd.copy(value = value.signExtended().s)
                            else -> `impossible!`
                        }.plusMeta(SIGN_EXTENDED_STORE)
                    )
                }

                is TACSymbol.Var -> {
                    val defPtr = graph.intraBlockDef(value, info.lcmd.ptr) ?: return false
                    val defLCmd = graph.elab(defPtr)
                    // A store of a signed variable may be a direct rewriting of a load of the same type.
                    loadInfoOrNull(defLCmd)?.let { loadInfo ->
                        if (loadInfo.type == info.type) {
                            return true
                        }
                    }
                    val defCmd = defLCmd.cmd as? AssignExpCmd ?: return false
                    if (defCmd.rhs is TACExpr.Sym.Const) {
                        patcher.replace(defPtr, defCmd.copy(rhs = defCmd.rhs.s.signExtended()))
                    } else {
                        val (v, mask) = defCmd.rhs.asMaskOrNull() ?: return false
                        if (mask == SignUtilities.maxUnsignedValueOfBitwidth(info.width)) {
                            patcher.replace(
                                defPtr,
                                defCmd.copy(rhs = TACExpr.BinOp.SignExtend((info.width / 8 - 1).asTACExpr, v))
                            )
                        }
                    }
                    patcher.replace(ptr, cmd.plusMeta(SIGN_EXTENDED_STORE))
                }
            }
            return true
        }


        fun rewrite() {
            // patcher.debugPrinter().print(code)
            method.updateCode(patcher.toPatchingProgram())
        }
    }


    /**
     * This just adds unsigned bounds on every load - i.e.,  keeps the solidity pattern for signed storage. Note that
     * this is still needed, because the storage splitter removed the unpacking logic, which had a side effect of
     * promising exactly these bounds.
     */
    private fun fallBackRewrite() {
        for (method in contract.getDeclaredMethods()) {
            val code = method.code as CoreTACProgram
            val patcher = ConcurrentPatchingProgram(code)
            code.analysisCache.graph.commands.parallelStream().forEach { lcmd ->
                loadInfoOrNull(lcmd)?.let { info ->
                    patcher.insertAfter(lcmd.ptr, WidthConstraints(info.lhs).unsigned(info.width))
                }
            }
            method.updateCode(patcher.toPatchingProgram())
        }
    }


}

