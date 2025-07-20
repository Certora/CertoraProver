/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence

import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.CommandWithRequiredDecls
import analysis.EthereumVariables
import analysis.icfg.Inliner
import analysis.maybeAnnotation
import analysis.snarrowOrNull
import datastructures.stdcollections.*
import evm.MASK_SIZE
import instrumentation.calls.CalldataEncoding
import verifier.equivalence.tracing.BufferTraceInstrumentation
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.flatten
import scene.ContractClass
import scene.TACMethod
import solver.CounterexampleModel
import tac.CallId
import tac.MetaKey
import tac.StartBlock
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.TACSymbol.Companion.atSync
import vc.data.codeFromCommandWithVarDecls
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.EquivalenceChecker.Companion.mergeCodes
import verifier.equivalence.summarization.CommonPureInternalFunction
import java.math.BigInteger
import java.util.stream.Collectors

/**
 * Responsible for generating the rule to be checked, handling inlining/environment setup, etc.
 */
class RuleGenerator(private val methodA: TACMethod, private val methodB: TACMethod) {

    private val contractA = methodA.getContainingContract() as ContractClass
    private val contractB = methodB.getContainingContract() as ContractClass


    @SuppressRemapWarning
    private data class ForInlining(
        val prog: CoreTACProgram,
        val proc: Procedure,
        val callId: CallId
    )

    private val contractAStorage = contractA.storage.stateVars().single()
    private val contractBStorage = contractB.storage.stateVars().single()

    private val contractATStorage = contractA.transientStorage.stateVars().single()
    private val contractBTStorage = contractB.transientStorage.stateVars().single()

    private val environmentVars = listOf(
        EthereumVariables.balance,
        EthereumVariables.nonce,
        EthereumVariables.extcodesize,
        EthereumVariables.extcodehash
    )

    private val environmentToSeed = environmentVars.map {
        it to it.copy(it.namePrefix + "!source")
    }

    private val invocationEnv = listOf(
        EthereumVariables.basefee,
        EthereumVariables.caller,
        EthereumVariables.blockhash,
        EthereumVariables.number,
        EthereumVariables.timestamp,
        EthereumVariables.callvalue,
        EthereumVariables.origin,
        EthereumVariables.blobbasefee,
        EthereumVariables.coinbase,
        EthereumVariables.difficulty,
        EthereumVariables.gasLimit,
        EthereumVariables.address
    )

    private val invocationEnvToSeed = invocationEnv.map {
        it to it.copy(it.namePrefix + "!source")
    }

    /**
     * The "initial storage". Picked arbitrarily to be equal to [methodA]'s storage.
     */
    private val storageBackup = TACSymbol.Var("certoraStorageSource", Tag.WordMap)

    /**
     * Ibid, but for transient storage
     */
    private val transientBackup = TACSymbol.Var("certoraTransientSource", Tag.WordMap)

    /**
     * The arbitrary calldata sent to the two methods.
     */
    private val theCalldata = TACSymbol.Var("certoraEquivInputCalldata", Tag.ByteMap)
    private val theCalldataSize = TACSymbol.Var("certoraEquivInputCalldataSize", Tag.Bit256)

    /**
     * Program which initializes immutables to be the same, adds
     * contraints on code sizes, sets up the environment, and so on.
     */
    private val setupProgram = run {

        val immutInit = contractA.src.immutables.mapToSet {
            it.varname
        }.let { aImmuts ->
            val bImmuts = contractB.src.immutables.mapToSet {
                it.varname
            }
            check(bImmuts == aImmuts)
            aImmuts.map { immName ->
                // this is very bad
                val aImmut = TACSymbol.immutable(immName, contractA.name)
                val bImmut = TACSymbol.immutable(immName, contractB.name)
                ExprUnfolder.unfoldPlusOneCmd("immutConstrain", TACExpr.BinRel.Eq(aImmut.asSym(), bImmut.asSym())) {
                    TACCmd.Simple.AssumeCmd(it.s, "constrain immutable $immName")
                }.merge(aImmut, bImmut)
            }.flatten()
        }

        val dummyIdx = TACKeyword.TMP(Tag.Bit256, "!initIdx")

        val initialization = environmentToSeed.map { (envVar, seed) ->
            envVar `=` seed
        }.flatten() andThen (storageBackup `=` contractAStorage) andThen (transientBackup `=` contractATStorage) andThen ExprUnfolder.unfoldPlusOneCmd(
            "balanceEquiv",
            with(TACExprFactTypeCheckedOnlyPrimitives) {
                Eq(
                    Select(EthereumVariables.balance.asSym(), (contractA.addressSym as TACSymbol).asSym()),
                    Select(EthereumVariables.balance.asSym(), (contractB.addressSym as TACSymbol).asSym())
                )
            }) {
            TACCmd.Simple.AssumeCmd(it.s, "equal balances")
        }.merge(contractA.addressSym, contractB.addressSym, EthereumVariables.balance) andThen (theCalldata `=` {
            TACExpr.MapDefinition(
                listOf(dummyIdx.asSym()),
                Ite(
                    Lt(dummyIdx.asSym(), theCalldataSize.asSym()),
                    TACExpr.Unconstrained(Tag.Bit256),
                    TACExpr.zeroExpr
                ), Tag.ByteMap
            )
        }).merge(theCalldataSize) andThen ExprUnfolder.unfoldPlusOneCmd("sighash", TACExprFactoryExtensions.run {
            (theCalldata.get(0) shiftRLog (256 - 32)) eq methodA.sigHash!!.n
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "set sighash")
        } andThen invocationEnvToSeed.mapNotNull { (env, seed) ->
            if(env.tag != Tag.Bit256) {
                return@mapNotNull null
            }
            val kwd = env.meta[TACSymbol.Var.KEYWORD_ENTRY]?.maybeTACKeywordOrdinal ?: return@mapNotNull null
            CommandWithRequiredDecls(
                TACCmd.Simple.AnnotationCmd(
                    EnvironmentRecord.META_KEY,
                    EnvironmentRecord(kwd, seed)
                ), seed
            )
        }.flatten() andThen ExprUnfolder.unfoldPlusOneCmd("senderConstrain", TACExprFactoryExtensions.run {
            invocationEnvToSeed.find { (orig, _) ->
                orig == EthereumVariables.caller
            }?.second!! le MASK_SIZE(160)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "sender is address")
        } andThen immutInit andThen ExprUnfolder.unfoldPlusOneCmd("sameAddress", TACExpr.BinRel.Eq(contractA.addressSym.asSym(), contractB.addressSym.asSym(), Tag.Bool)) {
            TACCmd.Simple.AssumeCmd(it.s, "contracts have same addresses")
        }
        val withDecl = initialization.merge(
            invocationEnvToSeed.flatMap { listOf(it.first, it.second) } + environmentToSeed.flatMap {
                listOf(it.first, it.second)
            }
        ).merge(
            storageBackup,
            transientBackup,
            contractAStorage,
            contractATStorage,
            contractBStorage,
            contractBTStorage
        )

        codeFromCommandWithVarDecls(StartBlock, withDecl, "setup")
    }

    /**
     * Inlines [code] which is assumed to be the instrumented version of [origMethod]. In addition to call indexing,
     * via [CoreTACProgram.createCopy], this handles setting up storage, adding push/pop records, etc.
     *
     * Basically a stripped down version of [Inliner.prepareMethodForInlining]
     */
    private fun setupAndPrepareForInlining(
        code: CoreTACProgram,
        contractStorage: TACSymbol.Var,
        contractTransientStorage: TACSymbol.Var,
        origMethod: TACMethod
    ) : ForInlining {
        val callId = Allocator.getFreshId(Allocator.Id.CALL_ID)
        val callee = code.createCopy(callId).let { withCopy ->
            withCopy.parallelLtacStream().mapNotNull {
                if(it.cmd.isHalting()) {
                    return@mapNotNull { patch: SimplePatchingProgram ->
                        patch.replaceCommand(it.ptr, listOf(TACCmd.Simple.NopCmd))
                    }
                }
                val summ = it.snarrowOrNull<CommonPureInternalFunction>() ?: return@mapNotNull null;
                { patch: SimplePatchingProgram ->
                    patch.replaceCommand(it.ptr, listOf(
                        TACCmd.Simple.AnnotationCmd(
                            CommonPureInternalFunction.ANNOTATION_META, summ
                        )
                    ))
                }
            }.patchForEach(withCopy, check = true) {
                it(this)
            }
        }.patching { patcher ->
            this.analysisCache.graph.sinks.forEach(
                Inliner.CallStack.stackPopper(
                    patcher, Inliner.CallStack.PopRecord(
                        callee = origMethod.toRef(),
                        callId
                    )
                )
            )
            this.analysisCache.graph.roots.forEach(
                Inliner.CallStack.stackPusher(
                    patcher, Inliner.CallStack.PushRecord(
                        callee = origMethod.toRef(),
                        calleeId = callId,
                        summary = null,
                        isNoRevert = false,
                        convention = Inliner.CallConventionType.Serialization
                    )
                )
            )
        }
        val environmentSetup = environmentToSeed.map { (lhs, rhs) ->
            lhs `=` rhs
        }.flatten() andThen invocationEnvToSeed.map { (lhs, rhs) ->
            lhs.at(callId) `=` rhs
        }.flatten() andThen (contractStorage `=` storageBackup) andThen
            (contractTransientStorage `=` transientBackup) andThen
            (TACKeyword.CALLDATA.toVar().atSync(callId) `=` theCalldata) andThen
            (TACKeyword.CALLDATASIZE.toVar().atSync(callId) `=` theCalldataSize) andThen
            (origMethod.calldataEncoding as CalldataEncoding).byteOffsetToScalar.map { (range, v) ->
                v.at(callId) `=` {
                    Select(theCalldata.asSym(), range.from.asTACExpr)
                }
            }.flatten()

        val withSetup = callee.prependToBlock0(environmentSetup)
        return ForInlining(
            prog = withSetup,
            callId = callId,
            proc = Procedure(callId, _method = origMethod),
        )
    }

    data class GeneratedRule(
        val code: CoreTACProgram,
        val methodACallId: CallId,
        val methodBCallId: CallId
    )

    /**
     * Records that [sym] holds the havoced value for the [TACKeyword] with enum ordinal [keywordOrd].
     *
     * Cursed!
     */
    data class EnvironmentRecord(
        val keywordOrd: Int,
        val sym: TACSymbol.Var
    ) : TransformableVarEntityWithSupport<EnvironmentRecord> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): EnvironmentRecord {
            return EnvironmentRecord(
                keywordOrd = keywordOrd,
                sym = f(sym)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(sym)

        companion object {
            val META_KEY = MetaKey<EnvironmentRecord>("equivalence.env")
        }
    }
    companion object {
        fun extractEnvironmentValues(
            model: CounterexampleModel,
            prog: CoreTACProgram
        ): Map<TACSymbol.Var, BigInteger> {
            return prog.parallelLtacStream().mapNotNull {
                it.maybeAnnotation(EnvironmentRecord.META_KEY)
            }.mapNotNull {
                val kwd = TACKeyword.entries[it.keywordOrd].toVar()
                model.valueAsBigInteger(it.sym).leftOrNull()?.let {
                    kwd to it
                }
            }.collect(Collectors.toMap({ it.first }, { it.second }))
        }
    }

    fun generateRule(
        methodAInst: BufferTraceInstrumentation.InstrumentationResults,
        methodBInst: BufferTraceInstrumentation.InstrumentationResults,
        vc: CoreTACProgram
    ): GeneratedRule {
        val setupA = setupAndPrepareForInlining(methodAInst.code, contractAStorage, contractATStorage, methodA)
        val setupB = setupAndPrepareForInlining(methodBInst.code, contractBStorage, contractBTStorage, methodB)

        val ruleStart = setupProgram

        val intermission = codeFromCommandWithVarDecls(
            Allocator.getNBId(),
            CommandWithRequiredDecls(TACCmd.Simple.LabelCmd("intermission")),
            "intermission"
        )

        return GeneratedRule(
            code = mergeCodes(
                ruleStart,
                mergeCodes(
                    setupA.prog,
                    mergeCodes(
                        intermission,
                        mergeCodes(setupB.prog, vc)
                    )
                )
            ), methodACallId = setupA.callId, methodBCallId = setupB.callId
        )
    }
}
