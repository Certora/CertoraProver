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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.icfg

import analysis.CommandWithRequiredDecls
import analysis.EthereumVariables
import analysis.LTACCmd
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import log.*
import scene.IScene
import utils.Range
import spec.cvlast.MaybeRevert
import spec.cvlast.SpecCallSummary
import tac.CallId
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger
import kotlin.collections.emptyList
import kotlin.collections.listOf
import kotlin.collections.mapOf
import kotlin.collections.mutableSetOf

private val logger = Logger(LoggerTypes.INLINER)

object Havocer {
    @KSerializable
    @Treapable
    sealed class HavocType : AmbiSerializable, MaybeRevert {
        sealed interface NotAffectingGlobalState

        /*
            Just havoc return values
       */
        @KSerializable
        object Static : HavocType(), NotAffectingGlobalState {
            override fun toString(): String = "only the return value"
            override fun hashCode() = hashObject(this)
            fun readResolve(): Any = Static
        }

        @KSerializable
        data class AssertFalse(val range: Range) : HavocType(), NotAffectingGlobalState {
            override fun isRevertable() = false
            override fun toString(): String = "A havoc that should be unreachable"
        }


        @KSerializable
        data class HavocOnly(val onlyAddressAndContractName: Map<BigInteger, String?>) : HavocType() {
            override fun isRevertable() = true
            override fun toString(): String =
                "only the contracts ${
                    onlyAddressAndContractName.keys.joinToString(separator = ", ") {
                        when (val contractName = onlyAddressAndContractName[it]) {
                            null -> it.toString(16)
                            else -> "$contractName (${it.toString(16)})"
                        }
                    }
                }"
        }

        @KSerializable
        data class AllExcept(val excludeAddressAndContractName: Map<BigInteger, String?>) : HavocType() {
            override fun isRevertable() = true
            override fun toString(): String =
                "all contracts except ${
                    excludeAddressAndContractName.keys.joinToString(separator = ", ") {
                        when (val contractName = excludeAddressAndContractName[it]) {
                            null -> it.toString(16)
                            else -> "$contractName (${it.toString(16)})"
                        }
                    }
                }"
        }

        /*
           Havoc literally everyone
         */
        @KSerializable
        object All : HavocType() {
            override fun isRevertable() = true
            override fun toString(): String = "All contracts"
            override fun hashCode() = hashObject(this)
            fun readResolve(): Any = All
        }

    }

    fun generateHavocBlock(
            sourceInstanceId: BigInteger,
            s: IScene,
            havoc: HavocType,
            callSummary: CallSummary,
            callContext: LTACCmd
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        val decl = mutableSetOf<TACSymbol.Var>()
        val commands = mutableListOf<TACCmd.Simple>()
        fun MutableSet<TACSymbol.Var>.add(v: TACSymbol) {
            if(v is TACSymbol.Var) {
                this.add(v)
            }
        }
        /* havoc return data */
        val callIndex = callContext.ptr.block.calleeIdx
        val returnDataVar = TACKeyword.RETURNDATA.toVar(callIndex = callIndex)
        commands.addAll(listOf(
                TACCmd.Simple.ByteLongCopy(
                        srcOffset = TACSymbol.lift(0),
                        length = callSummary.outSize,
                        dstOffset = callSummary.outOffset,
                        dstBase = callSummary.outBase,
                        // assumed that this is havoced on all paths
                        srcBase = returnDataVar
                )
        ))
        if (Config.SuperOptimisticReturnsize.get()) {
            commands.addAll(handleSuperOptimisticReturnsize(callSummary, callIndex))
        } else if (Config.OptimisticReturnsize.get()) { // TODO: Make this an option in a summary instead of global magic variable?
            commands.addAll(handleOptimisticReturnsize(callSummary, s, callIndex))
        } else {
            val (cmd, assume) = handleConservativeGasBasedReturnsize(callIndex)
            commands.addAll(cmd)
            decl.addAll(assume)
        }
        decl.add(callSummary.outBase)
        decl.add(callSummary.outOffset)
        decl.add(callSummary.outSize)
        decl.add(returnDataVar)
        decl.add(TACKeyword.RETURN_SIZE.toVar(callIndex = callIndex))

        fun allocTmp(s: String, t: Tag = Tag.Bit256) =
            TACKeyword.TMP(t, s).also {
                decl.add(it)
            }

        if (!havoc.isRevertable()) {
            // according to our documentation rc should be set to 1
            val rc = EthereumVariables.rc.at(callIndex = callContext.ptr.block.calleeIdx)
            commands.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    rc, TACSymbol.lift(1)
                )
            )
            decl.add(rc)
        }

        if(havoc != HavocType.Static) {
            val oldBalanceContract = allocTmp( "currBalanceContract")
            val oldBalanceCaller = allocTmp("currBalanceCaller")
            decl.add(EthereumVariables.balance)
            val sourceAddress = (s.getContract(sourceInstanceId).addressSym as TACSymbol).asSym()
            val getOldBalance = listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            oldBalanceContract,
                            TACExpr.Select(EthereumVariables.balance.asSym(), sourceAddress)
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            oldBalanceCaller,
                            TACExpr.Select(EthereumVariables.balance.asSym(), callSummary.toVar.asSym())
                    )
            )
            commands.addAll(getOldBalance)
            decl.add(callSummary.toVar)
            val havocBalance = listOf(
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(EthereumVariables.balance),
                SnippetCmd.EVMSnippetCmd.HavocBalanceSnippet.toAnnotation()
            )
            val assumeNonDecreasedThisBalance = allocTmp("isNonDecreasedBalance", Tag.Bool).let { b ->
                listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            b, TACExpr.BinRel.Le(
                                oldBalanceContract.asSym(), TACExpr.Select(
                                    EthereumVariables.balance.asSym(), sourceAddress
                                )
                            )
                        ), TACCmd.Simple.AssumeCmd(b)
                )
            }
            val assumeSameCalleeBalance = allocTmp("isIncreasedBalance", Tag.Bool).let { b ->
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        b, TACExpr.BinRel.Eq(
                            oldBalanceCaller.asSym(), TACExpr.Select(
                                EthereumVariables.balance.asSym(), callSummary.toVar.asSym()
                            )
                        )
                    ), TACCmd.Simple.AssumeCmd(b)
                )
            }
            commands.addAll(havocBalance)
            if (havoc != HavocType.All) {
                commands.addAll(assumeNonDecreasedThisBalance)
                commands.addAll(assumeSameCalleeBalance)
            }
        }

        if(havoc != HavocType.Static) {
            val havocCmdsAndDecls = generateHavocCRD(havoc, s)
            commands.addAll(havocCmdsAndDecls.cmds)
            decl.addAll(havocCmdsAndDecls.varDecls)

            // havoc ghosts
            if (!Config.TmpAllGhostsAreGlobal.get()) { // this should be removed once globals are introduced
                commands.add(TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_HAVOC))
            }
        }

        // add contracts variables to decl
        s.getContracts().forEach { (it.addressSym as? TACSymbol.Var)?.let { citr -> decl.add(citr) } }
        return CommandWithRequiredDecls(
            commands,
            decl
        )
    }

    fun generateHavocCRD(havoc: HavocType, scene: IScene): CommandWithRequiredDecls<TACCmd.Simple> {
        if (havoc is HavocType.AssertFalse) {
            return CommandWithRequiredDecls(
                TACCmd.Simple.AssertCmd(false.asTACSymbol(), "A havoc result that should be unreachable, due to summary in ${havoc.range}")
            )
        }
        val decl = mutableSetOf<TACSymbol.Var>()
        val addressesToHavocTheirStorage = when(havoc) {
            HavocType.All -> scene.getStorageUniverse().keys
            is HavocType.HavocOnly -> havoc.onlyAddressAndContractName.keys
            is HavocType.AllExcept -> scene.getStorageUniverse().keys - havoc.excludeAddressAndContractName.keys
            is HavocType.NotAffectingGlobalState -> `impossible!`
        }
        val havocStorages = addressesToHavocTheirStorage.flatMap { addr ->
            val ret = mutableListOf<TACCmd.Simple>()
            EthereumVariables.getStorageInternal(addr).let {
                ret.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(it))
                ret.add(SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageHavocContract(addr).toAnnotation())
                decl.add(it)
            }
            val storageUniverse = scene.getUnifiedStorageInfoViewWithReadTrackers()[addr] as StorageInfoWithReadTracker?
            storageUniverse?.storageVariables
                ?.forEachEntry { (sv, readTracker) ->
                    ret.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(sv))
                    if(readTracker != null) {
                        ret.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(readTracker))
                        decl.add(readTracker)
                    }
                    decl.add(sv)
                }
            ret
        }
        return CommandWithRequiredDecls(
            havocStorages,
            decl
        )
    }

    private const val GAS_BASED_RETURN_POW = 32

    private fun handleConservativeGasBasedReturnsize(
        callIndex: CallId
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        /*
         NB (jtoman): we can assume a reasonable upper bound on the size of the return buffer given back from
         the external contract based on current (as of Jan 2022) block gas limits, around 15 million.

          It's easy enough to show that the gas cost to return a buffer of size n bytes incurs a cost of
          at least:
          (n / 32) * 3 + ((n / 32) / 512) ^ 2

          where n / 32 is the number of words of memory, and the linear equation is based on the memory expansion
          cost function given in the yellow paper. It is simple enough then to solve the following equation:

          n * (3 / 32) + (n^2 / 16384) - 1.5 * 10^12 = 0

          where 1.5 * 10^2 is 1.5 trillion, many orders of magnitude above the block gas limit.

          Solving for n (see https://www.wolframalpha.com/input/?i=solve+x+*+%283%2F32%29+%2B+x%5E2+%2F+16384+-+1.5+*+10%5E12+%3D+0) gives:
          n = 1.56767×10^8 or around 156 million. Thus, we can choose our upper length limit to be 2^32,
          and be confident there is nowhere near enough gas in a block to actually generate a return buffer of that size.
         */
        val tmp = TACKeyword.TMP(Tag.Bool, suffix = "!returnAssume").toUnique()
        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = tmp,
                    rhs = TACExpr.BinRel.Lt(
                        TACKeyword.RETURN_SIZE.toVar(callIndex = callIndex).asSym(),
                        BigInteger.TWO.pow(GAS_BASED_RETURN_POW).asTACSymbol().asSym()
                    )
                ),
                TACCmd.Simple.AssumeCmd(
                    cond = tmp
                )
            ), tmp
        )
    }

    /**
     * Assume returnsize is the caller's call instruction outsize
     */
    private fun handleSuperOptimisticReturnsize(
        callSummary: CallSummary,
        callIndex: CallId
    ): List<TACCmd.Simple> {
        return listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                TACKeyword.RETURN_SIZE.toVar(callIndex = callIndex),
                callSummary.outSize
            )
        )
    }

    /**
     * If the [callSummary]'s signature only resolves to functions that in our scene [s] have the same size, assign that size
     * to RETURNSIZE and return it. Otherwise, return the empty list.
     */
    private fun handleOptimisticReturnsize(
        callSummary: CallSummary,
        s: IScene,
        callIndex: CallId
    ): List<TACCmd.Simple> {
        if (callSummary.sigResolution.size == 1) {
            val sig = callSummary.sigResolution.first()
            val methodsMatchingInScene = sig?.let { s.getMethods(it) }
            val types = methodsMatchingInScene?.map { it.evmExternalMethodInfo?.resultTypes }?.uniqueOrNull()
            val sz = types?.let { resultTypes ->
                resultTypes.sumOf {
                    it.getMinimumEncodingSize()
                }
            }
            logger.info { "Got types $types and $sz for $sig in call id $callIndex" }
            if (sz != null) {
                Logger.regression { "In havoc call to ${sig.toString(16)}, all scene matches have the same return size $sz (for $types)" }
                return listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        TACKeyword.RETURN_SIZE.toVar(callIndex = callIndex),
                        sz.asTACSymbol()
                    )
                )
            }
        }

        return emptyList()
    }

    fun summaryToHavoc(
        scene: IScene,
        caller: BigInteger,
        callSummary: CallSummary,
        havocSpecCallSummary: SpecCallSummary.HavocSummary,
        context: LTACCmd
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val havocType = resolveHavocType(scene, caller, callSummary, havocSpecCallSummary)
        return generateHavocBlock(caller, scene, havocType, callSummary, context)
    }

    fun resolveHavocType(
        scene: IScene,
        caller: BigInteger,
        callSummary: CallSummary,
        havocSpecSummary: SpecCallSummary.HavocSummary? = null
    ): HavocType =
        if(havocSpecSummary == null && callSummary.callType == TACCallType.STATIC) {
            HavocType.Static
        } else {
            when (havocSpecSummary) {
                null -> defaultHavoc(scene, caller, callSummary)
                is SpecCallSummary.HavocSummary.HavocECF -> {
                    HavocType.AllExcept(mapOf(caller to scene.getContractOrNull(caller)?.name))
                }
                is SpecCallSummary.HavocSummary.HavocAll -> {
                    HavocType.All
                }
                is SpecCallSummary.HavocSummary.Nondet -> {
                    HavocType.Static
                }
                is SpecCallSummary.HavocSummary.Auto -> {
                    defaultHavoc(scene, caller, callSummary)
                }
                is SpecCallSummary.HavocSummary.AssertFalse -> {
                    HavocType.AssertFalse(havocSpecSummary.range)
                }
            }
        }

    private fun defaultHavoc(
        scene: IScene,
        caller: BigInteger,
        callSummary: CallSummary
    ): HavocType {
        return when (callSummary.callType) {
            TACCallType.STATIC -> HavocType.Static
            TACCallType.REGULAR_CALL,
            TACCallType.CREATE -> HavocType.AllExcept(mapOf(caller to scene.getContractOrNull(caller)?.name))
            TACCallType.DELEGATE,
            TACCallType.CALLCODE -> HavocType.HavocOnly(mapOf(caller to scene.getContractOrNull(caller)?.name))
        }
    }
}
