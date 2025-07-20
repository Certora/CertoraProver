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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
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
import rules.CompiledRule
import solver.CounterexampleModel
import tac.CallId
import tac.MetaKey
import tac.StartBlock
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.codeFromCommandWithVarDecls
import vc.data.tacexprutil.ExprUnfolder
import verifier.equivalence.data.*
import verifier.equivalence.summarization.CommonPureInternalFunction
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`
import java.math.BigInteger
import java.util.stream.Collectors

/**
 * Abstract class responsible for stitching together the instrumented versions of A and B
 * and the generated VC. [I] is the type of the calling convention for programs A and B.
 *
 * This class will handle setting the environment variables to be equivalent (and saving their values
 * for retrieval later from the CEX), setting immutables to be equivalent, storage and transient storage to be equal,
 * setting balances to be equal, and setting the contract addresses to be equal. Binding the calling information
 * is left to the subclasses.
 */
abstract class AbstractRuleGeneration<I>(
    protected val context: EquivalenceQueryContext
) {
    /**
     * Setup code that is always placed before the instrumented bodies are inlined. This code is expected to initialize
     * the common inputs that will be passed to both programs via [generateInput].
     */
    abstract fun setupCode(): CommandWithRequiredDecls<TACCmd.Simple>

    /**
     * Bind the inputs/arguments of [p] using the context [context]. [p] has been call-indexed to use [callId];
     * however the calling convention information in [p] has not been automatically updated to reflect this call-indexing (as
     * [I] is an unknown type to this class).
     * The returned [CommandWithRequiredDecls] should bind the arguments/inputs of [p], and is automatically prepended
     * onto [p] by this function's caller.
     */
    abstract fun <T: MethodMarker> generateInput(
        p: CallableProgram<T, I>,
        context: ProgramContext<T>,
        callId: CallId
    ) : CommandWithRequiredDecls<TACCmd.Simple>

    /**
     * The program [inlined] has been call indexed with [callId] and had its arguments bound via [callingConv].
     * This function may (or may not) annotate the entrance/exit points of the resulting program, e.g. for call trace purposes.
     */
    abstract fun <T: MethodMarker> annotateInOut(
        inlined: CoreTACProgram,
        callingConv: I,
        context: ProgramContext<T>,
        callId: CallId
    ) : CoreTACProgram


    @SuppressRemapWarning
    private data class ForInlining(
        val prog: CoreTACProgram,
        val proc: Procedure,
        val callId: CallId
    )

    private val contractA get() = context.contextA.hostContract
    private val contractB get() = context.contextB.hostContract

    private val contractAStorage = context.contextA.storageVariable
    private val contractATStorage = context.contextA.transientStorageVariable

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
     * The "initial storage". Picked arbitrarily to be equal to [context].[EquivalenceQueryContext.contextA]'s storage.
     */
    private val storageBackup = TACSymbol.Var("certoraStorageSource", Tag.WordMap)

    /**
     * Ibid, but for transient storage
     */
    private val transientBackup = TACSymbol.Var("certoraTransientSource", Tag.WordMap)

    /**
     * Program which initializes immutables to be the same, adds
     * contraints on code sizes, sets up the environment, and so on.
     */
    private val setupProgram by lazy {

        /**
         * Constrain the immutables to be the same (throws if there are different immutables)
         */
        val immutEquivalence = context.contextA.hostContract.src.immutables.mapToSet {
            it.varname
        }.let { aImmuts ->
            val bImmuts = context.contextB.hostContract.src.immutables.mapToSet {
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

        /**
         * Constrain the host contracts values to be equivalent (we technically only need one of these
         * equalities, as we constrain the address syms to be equivalent later)
         */
        val balanceEquivalence = ExprUnfolder.unfoldPlusOneCmd(
            "balanceEquiv",
            with(TACExprFactTypeCheckedOnlyPrimitives) {
                Eq(
                    Select(EthereumVariables.balance.asSym(), (contractA.addressSym as TACSymbol).asSym()),
                    Select(EthereumVariables.balance.asSym(), (contractB.addressSym as TACSymbol).asSym())
                )
            }) {
            TACCmd.Simple.AssumeCmd(it.s, "equal balances")
        }.merge(contractA.addressSym, contractB.addressSym, EthereumVariables.balance)


        /**
         * Set invocation environment to predetermined values, and record values in [EnvironmentRecord]
         */
        val invocationEnvSetup = invocationEnvToSeed.mapNotNull { (env, seed) ->
            if (env.tag != Tag.Bit256) {
                return@mapNotNull null
            }
            val kwd = env.meta[TACSymbol.Var.KEYWORD_ENTRY]?.maybeTACKeywordOrdinal ?: return@mapNotNull null
            CommandWithRequiredDecls(
                TACCmd.Simple.AnnotationCmd(
                    EnvironmentRecord.META_KEY,
                    EnvironmentRecord(kwd, seed)
                ), seed
            )
        }.flatten()

        // set environment to predetermined values
        val environmentSetup = environmentToSeed.map { (envVar, seed) ->
            envVar `=` seed
        }.flatten()
        // constrain the msg.sender to be a value address
        val senderIsAddress = ExprUnfolder.unfoldPlusOneCmd("senderConstrain", TACExprFactoryExtensions.run {
            invocationEnvToSeed.find { (orig, _) ->
                orig == EthereumVariables.caller
            }?.second!! le MASK_SIZE(160)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "sender is address")
        }

        // constrain the contracts to have the same address (value of `this`)
        val hostAddressEquivalence = ExprUnfolder.unfoldPlusOneCmd(
            "sameAddress",
            TACExpr.BinRel.Eq(contractA.addressSym.asSym(), contractB.addressSym.asSym(), Tag.Bool)
        ) {
            TACCmd.Simple.AssumeCmd(it.s, "contracts have same addresses")
        }
        val initialization = environmentSetup andThen
            (storageBackup `=` contractAStorage) andThen
            (transientBackup `=` contractATStorage) andThen
            balanceEquivalence andThen
            invocationEnvSetup andThen
            immutEquivalence andThen
            senderIsAddress andThen
            hostAddressEquivalence

        val withDecl = initialization.merge(
            invocationEnvToSeed.flatMap { listOf(it.first, it.second) } + environmentToSeed.flatMap {
                listOf(it.first, it.second)
            }
        ).merge(
            storageBackup,
            transientBackup,
            contractAStorage,
            contractATStorage,
        )

        val customSetup = setupCode()

        codeFromCommandWithVarDecls(StartBlock, withDecl andThen customSetup, "setup")
    }

    /**
     * Inlines [programAndId]. In addition to call indexing,
     * via [CoreTACProgram.createCopy], binds arguments annotates input/outputs via [generateInput] and [annotateInOut].
     *
     * Basically a stripped down version of [Inliner.prepareMethodForInlining]
     */
    private fun <T: MethodMarker> setupAndPrepareForInlining(
        programAndId: CallableProgram<T, I>,
        context: ProgramContext<T>
    ) : ForInlining {
        val code = programAndId.program
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
        }.let { forAnnot ->
            annotateInOut(
                context = context,
                callingConv = programAndId.callingInfo,
                inlined = forAnnot,
                callId = callId
            )
        }

        val argumentSetup = generateInput(
            callId = callId,
            context = context,
            p = object : CallableProgram<T, I> {
                override val program: CoreTACProgram
                    get() = callee
                override val callingInfo: I
                    get() = programAndId.callingInfo

                override fun pp(): String {
                    return programAndId.pp()
                }
            }
        )

        val environmentSetup = environmentToSeed.map { (lhs, rhs) ->
            lhs `=` rhs
        }.flatten() andThen invocationEnvToSeed.map { (lhs, rhs) ->
            lhs.at(callId) `=` rhs
        }.flatten() andThen (context.storageVariable `=` storageBackup) andThen
            (context.transientStorageVariable `=` transientBackup) andThen
            argumentSetup

        val withSetup = callee.prependToBlock0(environmentSetup)
        return ForInlining(
            prog = withSetup,
            callId = callId,
            proc = Procedure(callId, ProcedureId.EquivProgram(
                name = programAndId.pp(),
                hostContract = context.hostContract.instanceId
            )),
        )
    }

    /**
     * [code] holds the instrumented programs and rules inlined together and with arguments bound; i.e., a self-contained
     * program checking the VC which can be passed to [verifier.TACVerifier.verify]. The call ids for program A and B
     * are [methodACallId] and [methodBCallId].
     */
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

    /**
     * Given A and B callable programs versions [aCode] and [bCode], and a [vc] program asserting their equivalence, return the [GeneratedRule]
     * which binds arguments, constrains storage, etc. This function also runs the resulting rule through the standard
     * [CompiledRule.optimize] pipeline, as that contains many CVL agnostic optimizations crucial to performance.
     */
    fun generateRule(
        aCode: CallableProgram<MethodMarker.METHODA, I>,
        bCode: CallableProgram<MethodMarker.METHODB, I>,
        vc: CoreTACProgram
    ) : GeneratedRule {
        val setupA = setupAndPrepareForInlining(aCode, context.contextA)
        val setupB = setupAndPrepareForInlining(bCode, context.contextB)

        val ruleStart = setupProgram

        val intermission = codeFromCommandWithVarDecls(
            Allocator.getNBId(),
            CommandWithRequiredDecls(TACCmd.Simple.LabelCmd("intermission")),
            "intermission"
        )

        val ruleCode = EquivalenceChecker.mergeCodes(
            ruleStart,
            EquivalenceChecker.mergeCodes(
                setupA.prog,
                EquivalenceChecker.mergeCodes(
                    intermission,
                    EquivalenceChecker.mergeCodes(setupB.prog, vc)
                )
            )
        ).let {
            CompiledRule.optimize(context.scene.toIdentifiers(), it, bmcMode = false)
        }
        return GeneratedRule(
            code = ruleCode, methodACallId = setupA.callId, methodBCallId = setupB.callId
        )
    }
}
