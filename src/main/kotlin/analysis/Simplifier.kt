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

package analysis

import allocator.Allocator
import config.Config
import config.Config.BalanceCondCheckIfCallerIsCalleeForOverflow
import config.Config.Mem0x0To0x40AsScalar
import config.ConfigType
import config.ReportTypes
import datastructures.stdcollections.*
import evm.*
import log.*
import spec.CVLKeywords
import spec.EVMConfig
import spec.toVar
import tac.*
import testing.TacPipelineDebuggers.twoStateInvariant
import utils.*
import vc.data.*
import vc.data.TACMeta.CODEDATA_KEY
import vc.data.TACMeta.CODESIZE_KEY
import vc.data.TACMeta.CODESIZE_VALUE
import vc.data.TACMeta.CONSTRUCTOR_CODE_KEY
import vc.data.TACMeta.EXTCODE_DATA_KEY
import vc.data.TACMeta.IS_STORAGE_ACCESS
import vc.data.TACMeta.IS_TRANSIENT_STORAGE_ACCESS
import vc.data.TACMeta.NO_CALLINDEX
import vc.data.TACMeta.RETURN_PATH
import vc.data.TACMeta.REVERT_PATH
import vc.data.TACMeta.TRANSFERS_BALANCE
import vc.data.TACProgramCombiners.andThen
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.TACExprFactSimple
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

object EthereumVariables {

    internal fun getStorageInternal(address: BigInteger) =
        TACSymbol.Var(TACKeyword.STORAGE.extendName("!${address.toString(16)}"), Tag.WordMap)
            .withMeta(TACMeta.STORAGE_KEY, address)
            .withMeta(TACMeta.SCALARIZATION_SORT, ScalarizationSort.UnscalarizedBuffer)

    internal fun getTransientStorageInternal(address: BigInteger) =
        TACSymbol.Var(TACKeyword.TRANSIENT_STORAGE.extendName("!${address.toString(16)}"), Tag.WordMap)
            .withMeta(TACMeta.TRANSIENT_STORAGE_KEY, address)
            .withMeta(TACMeta.SCALARIZATION_SORT, ScalarizationSort.UnscalarizedBuffer)

    private fun getSize(t: TACKeyword, address: BigInteger) = TACSymbol.Var(
        t.extendName("!${address.toString(16)}"),
        tag = t.type,
        meta = t.metaMap.plus(TACMeta.CODESIZE_KEY to address)
    ).withMeta(NO_CALLINDEX)

    fun getCodeDataSize(address: BigInteger) = getSize(TACKeyword.CODESIZE, address)

    fun getCodeData(address: BigInteger) = TACSymbol.Var(
        TACKeyword.CODEDATA.extendName("!${address.toString(16)}"),
        tag = TACKeyword.CODEDATA.type,
        meta = TACKeyword.CODEDATA.metaMap.plus(
            TACMeta.CODEDATA_KEY to address
        ).plus(
            MetaMap(TACMeta.EVM_IMMUTABLE_ARRAY to ImmutableBound(
                getSize(TACKeyword.CODESIZE, address)
            )
        ))
    )

    fun getConstructorCodeDataSize(address: BigInteger) =
        getSize(TACKeyword.CONSTRUCTOR_CODESIZE, address).withMeta(CONSTRUCTOR_CODE_KEY)

    fun getConstructorCodeData(address: BigInteger) = TACSymbol.Var(
        TACKeyword.CONSTRUCTOR_CODEDATA.extendName("!${address.toString(16)}"),
        tag = TACKeyword.CONSTRUCTOR_CODEDATA.type,
        meta = TACKeyword.CONSTRUCTOR_CODEDATA.metaMap.plus(
            CODEDATA_KEY to address
        ).plus(CONSTRUCTOR_CODE_KEY)
    )

    /**
     * @param resultVarTag the result of this function should have this [Tag]
     */
    fun constructOriginalStorageVar(classId: BigInteger, resultVarTag: Tag): TACSymbol.Var {
        val c = Allocator.getAndIncStorageCounter()
        return TACSymbol.Var(
            TACKeyword.ORIGINAL_STORAGE.extendName("!${classId.toString(16)}!${c}"),
            resultVarTag
        ).withMeta(TACMeta.ORIGINAL_STORAGE_KEY, classId)
    }

    fun constructOriginalTransientStorageVar(classId: BigInteger, resultVarTag: Tag): TACSymbol.Var {
        val c = Allocator.getAndIncTransientStorageCounter()
        return TACSymbol.Var(
            TACKeyword.ORIGINAL_TRANSIENT_STORAGE.extendName("!${classId.toString(16)}!${c}"),
            resultVarTag
        ).withMeta(TACMeta.ORIGINAL_TRANSIENT_STORAGE_KEY, classId)
    }

    // we do not really support doublemaps in general, so to support extcodedata,
    // we prefer to start with something overapproximative.
    // theoretically if the address is a Const, we could have just resolved it from the scene.
    // if not,we could do aliasing axioms based on the 4 arguments.
    fun createExtcodedata(addr: TACSymbol) = TACKeyword.EXTCODEDATA.toVar().withMeta(EXTCODE_DATA_KEY, addr)

    val address = TACKeyword.ADDRESS.toVar()
    val memory = TACKeyword.MEMORY.toVar()
    val balance = TACKeyword.BALANCE.toVar()
    val creationCount = TACKeyword.CONTRACT_COUNT.toVar()
    val origin = TACKeyword.ORIGIN.toVar()
    val gasLimit = TACKeyword.GASLIMIT.toVar()
    val chainId = TACKeyword.CHAINID.toVar()
    val basefee = TACKeyword.BASEFEE.toVar()
    val blobHashes = TACKeyword.BLOBHASHES.toVar()
    val blobbasefee = TACKeyword.BLOBBASEFEE.toVar()
    val difficulty = TACKeyword.DIFFICULTY.toVar()
    val extcodehash = TACKeyword.EXTCODEHASH.toVar()
    val number = TACKeyword.NUMBER.toVar()
    val timestamp = TACKeyword.TIMESTAMP.toVar()
    val coinbase = TACKeyword.COINBASE.toVar()
    val blockhash = TACKeyword.BLOCKHASH.toVar()
    val extcodesize = TACKeyword.EXTCODESIZE.toVar()

    val gasprice = TACKeyword.GASPRICE.toVar()
    val codesize = TACKeyword.CODESIZE.toVar()
    val calldatasize = TACKeyword.CALLDATASIZE.toVar()
    val calldata = TACKeyword.CALLDATA.toVar()
    val callvalue = TACKeyword.CALLVALUE.toVar()
    val caller = TACKeyword.CALLER.toVar()
    val returnsize = TACKeyword.RETURN_SIZE.toVar()
    val returndata = TACKeyword.RETURNDATA.toVar()
    val rc = TACKeyword.RETURNCODE.toVar()

    val nonce = TACKeyword.NONCE.toVar()

    @Suppress("unused")
    val counts = TACKeyword.CONTRACT_COUNT.toVar()

    fun getRC() =
        CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(rc), rc)


    fun simplifyIsZero(c: TACCmd.EVM.AssignIszeroCmd): CommandWithRequiredDecls<TACCmd.Simple> =
        CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                c.lhs,
                TACExpr.BinRel.Eq(
                    c.op1.asSym(),
                    TACSymbol.Const(BigInteger.ZERO).asSym()
                )
            )
        )

    fun simplifyAddress(c: TACCmd.EVM.AssignAddressCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, address, c.meta)),
            setOf(address)
        )

    fun simplifyBalance(c: TACCmd.EVM.AssignBalanceCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.WordLoad(c.lhs, c.op1, balance, c.meta)),
            setOf(balance)
        )

    fun simplifyOrigin(c: TACCmd.EVM.AssignOriginCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, origin, c.meta)),
            setOf(origin)
        )

    fun simplifyGaslimit(c: TACCmd.EVM.AssignGaslimitCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, gasLimit, c.meta)),
            setOf(gasLimit)
        )

    fun simplifyChainid(c: TACCmd.EVM.AssignChainidCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, chainId, c.meta)),
            setOf(chainId)
        )

    fun simplifySelfBalance(c: TACCmd.EVM.AssignSelfBalanceCmd) =
        simplifyBalance(TACCmd.EVM.AssignBalanceCmd(c.lhs, address, c.meta)).merge(address)

    fun simplifyBasefee(c: TACCmd.EVM.AssignBasefeeCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, basefee, c.meta)),
            setOf(basefee)
        )

    fun simplifyBlobhash(c: TACCmd.EVM.AssignBlobhashCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.WordLoad(c.lhs, c.index, blobHashes, c.meta)),
            setOf(blobHashes)
        )

    fun simplifyBlobbasefee(c: TACCmd.EVM.AssignBlobbasefeeCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, blobbasefee, c.meta)),
            setOf(blobbasefee)
        )

    fun simplifyDifficulty(c: TACCmd.EVM.AssignDifficultyCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, difficulty, c.meta)),
            setOf(difficulty)
        )

    fun simplifyNumber(c: TACCmd.EVM.AssignNumberCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs.withMeta(TACMeta.FOUNDRY_PROTECTED), number, c.meta)),
            setOf(number)
        )

    fun simplifyTimestamp(c: TACCmd.EVM.AssignTimestampCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs.withMeta(TACMeta.FOUNDRY_PROTECTED), timestamp, c.meta)),
            setOf(timestamp)
        )

    fun simplifyCoinbase(c: TACCmd.EVM.AssignCoinbaseCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, coinbase, c.meta)),
            setOf(coinbase)
        )

    fun simplifyBlockhash(c: TACCmd.EVM.AssignBlockhashCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.WordLoad(c.lhs, c.op, blockhash, c.meta)),
            setOf(blockhash)
        )

    fun simplifyExtcodecopy(c: TACCmd.EVM.ExtcodecopyCmd) =
        createExtcodedata(c.op1).let { extcodedata ->
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.ByteLongCopy(
                        c.op2,
                        c.op3,
                        c.op4,
                        c.memBaseMap,
                        extcodedata,
                        c.meta
                    ) // there's a soundness mistake here. also should be op2,op3,op4
                ),
                setOf(extcodedata)
            )
        }

    fun simplifyExtcodesize(c: TACCmd.EVM.AssignExtcodesizeCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.WordLoad(c.lhs, c.op1, extcodesize, c.meta)),
            setOf(extcodesize)
        )

    fun simplifyGasprice(c: TACCmd.EVM.AssignGaspriceCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, gasprice, c.meta)),
            setOf(gasprice)
        )

    fun simplifyCodesize(c: TACCmd.EVM.AssignCodesizeCmd) : CommandWithRequiredDecls<TACCmd.Simple> {
        val codesizeValueFromCmd = c.sz
        val address = c.meta.find(CODESIZE_KEY) ?: error("Codesize command must contain CODEDATA_KEY meta")
        // the decompiler will propagate the codesize to the AssignCodesizeCmd command as an argument,
        // and now we put it onto the codesize variable as meta where it should naturally be
        val codesizeWithMeta = getCodeDataSize(address).withMeta(CODESIZE_VALUE, codesizeValueFromCmd)

        return CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                c.lhs, codesizeWithMeta, c.meta)
            ),
            setOf(codesizeWithMeta)
        )
    }

    fun simplifyExtcodehash(c: TACCmd.EVM.AssignExtcodehashCmd) =
        CommandWithRequiredDecls(
            listOf(TACCmd.Simple.AssigningCmd.WordLoad(c.lhs, c.op1, extcodehash, c.meta)),
            setOf(extcodehash)
        )

    fun simplifyCodecopy(
        c: TACCmd.EVM.CodecopyCmd
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val address = c.meta.find(CODEDATA_KEY) ?: error("Codecopy command must contain CODEDATA_KEY meta")
        return CommandWithRequiredDecls(

            listOf(TACCmd.Simple.ByteLongCopy(c.op1, c.op2, c.op3, c.memBaseMap, getCodeData(address), c.meta)),
            setOf(getCodeData(address))
        )
    }

    fun simplifyCalldatasize(c: TACCmd.EVM.AssignCalldatasizeCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, calldatasize, c.meta),
        calldatasize
    )

    fun simplifyCalldataload(c: TACCmd.EVM.AssignCalldataloadCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.AssigningCmd.ByteLoad(c.lhs, c.op1, calldata, c.meta),
        calldata
    )

    fun simplifyCalldatacopy(c: TACCmd.EVM.CalldatacopyCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.ByteLongCopy(c.op1, c.op2, c.op3, c.memBaseMap, calldata, c.meta),
        calldata
    )

    fun simplifyCallvalue(c: TACCmd.EVM.AssignCallvalueCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, callvalue, c.meta)),
            setOf(callvalue)
        )

    fun simplifyCaller(c: TACCmd.EVM.AssignCallerCmd) =
        CommandWithRequiredDecls(

            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, caller, c.meta)),
            setOf(caller)
        )

    fun simplifyReturnsize(c: TACCmd.EVM.ReturndatasizeCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, returnsize, c.meta),
        returnsize
    )

    fun simplifyReturndatacopy(c: TACCmd.EVM.ReturndatacopyCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.ByteLongCopy(c.o1, c.o2, c.o3, c.memBaseMap, returndata, c.meta),
        returndata
    )

    fun simplifyMcopy(c: TACCmd.EVM.McopyCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        // Memory splitting assumes one memory access per ByteLongCopy; we ensure this by copying through a temporary
        // non-memory buffer.
        val tmp = TACKeyword.TMP(Tag.ByteMap, "!mcopy").toUnique("!").withMeta(TACMeta.MCOPY_BUFFER)
        return CommandWithRequiredDecls<TACCmd.Simple>(
            listOf(
                TACCmd.Simple.ByteLongCopy(TACSymbol.Zero, c.src, c.len, tmp, c.memBaseMap, c.meta),
                TACCmd.Simple.ByteLongCopy(c.dst, TACSymbol.Zero, c.len, c.memBaseMap, tmp, c.meta)
            ),
            tmp
        )
    }

    /** Originally the TransferBalance command.
     * When generated,
    should be accompanied by code that *reverts* in cases where
    either (1) src does not have amount ether;
    (2) trg overflows (check in yellowpaper - 2 is not mentioned)
     * We choose to revert, because there could always be some initial state that yields such an exception.
     * However, users of the system are still free to add their own assertions
     * about validity of the transfer in order to throw, or to assume it.
     */
    fun transferBalance(src: TACExpr, trg: TACExpr.Sym, amount: TACExpr): CommandWithRequiredDecls<TACCmd.Simple> {
        fun getBalanceAt(l: TACExpr): TACExpr.Select = TACExpr.Select(balance.asSym(), l)
        val srcSym = TACKeyword.TMP(Tag.Bit256, "SrcAddress")
        val l = mutableListOf<TACCmd.Simple>()
        val decls = mutableSetOf<TACSymbol.Var>()
        val bifs = mutableSetOf<FunctionInScope.Builtin>()
        val meta = MetaMap(TRANSFERS_BALANCE)
        decls.add(srcSym)
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                srcSym,
                src
            )
        )
        val amountSym = TACKeyword.TMP(Tag.Bit256, "TransferredAmount")
        decls.add(amountSym)
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                amountSym,
                amount
            )
        )
        // compute src
        val srcNewValue = TACKeyword.TMP(Tag.Int, "SrcNewValue")
        val srcOldValue = TACKeyword.TMP(Tag.Bit256, "SrcOldValue")
        decls.add(srcNewValue)
        decls.add(srcOldValue)
        val balanceAtSrc = getBalanceAt(src)
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                srcOldValue,
                balanceAtSrc
            )
        )
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                srcNewValue,
                TACExpr.BinOp.IntSub(
                    srcOldValue.asSym(), amountSym.asSym()
                ),
                meta
            )
        )

        // store for src
        if (src is TACExpr.Sym) {
            l.add(TACCmd.Simple.WordStore(src.s, srcNewValue, balance, meta))
        } else {
            val tmp = TACKeyword.TMP(Tag.Bit256, "Src")
            decls.add(tmp)
            l.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, src, meta)
            )
            l.add(
                TACCmd.Simple.WordStore(tmp, srcNewValue, balance, meta)
            )
        }

        // compute trg
        val trgNewValueInt = TACKeyword.TMP(Tag.Int, "TrgNewValueInt")
        val trgNewValue = TACKeyword.TMP(Tag.Bit256, "TrgNewValue")
        val trgOldValue = TACKeyword.TMP(Tag.Bit256, "TrgOldValue")
        val trgNoOverflow = TACKeyword.TMP(Tag.Bool, "trgNewBalanceNoofl")
        decls.add(trgNewValueInt)
        decls.add(trgNewValue)
        decls.add(trgOldValue)
        decls.add(trgNoOverflow)
        bifs.add(TACBuiltInFunction.NoAddOverflowCheck(Tag.Bit256).toInScopeBuiltinFunc())
        val balanceAtTrg = getBalanceAt(trg)
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                trgOldValue,
                balanceAtTrg
            )
        )
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                trgNewValueInt,
                TACExpr.Vec.IntAdd(
                    trgOldValue.asSym(), amountSym.asSym()
                ),
                meta
            )
        )
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                trgNoOverflow,
                TACExpr.Apply(
                    TACBuiltInFunction.NoAddOverflowCheck(Tag.Bit256).toTACFunctionSym(),
                    listOf(trgOldValue.asSym(), amountSym.asSym()),
                    Tag.Bool
                ),
                meta
            )
        )
        l.add(
            TACCmd.Simple.AssumeCmd(trgNoOverflow, "trgNoOverflow", meta)
        )
        l.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                trgNewValue,
                TACExpr.Apply(
                    TACBuiltInFunction.SafeMathNarrow(Tag.Bit256).toTACFunctionSym(),
                    listOf(trgNewValueInt.asSym()),
                    Tag.Bit256
                ),
                meta
            )
        )

        // store for trg
        l.add(TACCmd.Simple.WordStore(trg.s, trgNewValue, balance, meta))
        l.add(
            SnippetCmd.EVMSnippetCmd.TransferSnippet(
                srcBalanceOld = srcOldValue,
                srcBalanceNew = srcNewValue,
                trgBalanceOld = trgOldValue,
                trgBalanceNew = trgNewValue,
                addrSrc = srcSym,
                addrTrg = trg.s,
                transferredAmount = amountSym
            ).toAnnotation()
        )
        return CommandWithRequiredDecls(l, decls)
    }

    fun transferBalance(src: TACSymbol, trg: TACSymbol, amount: TACSymbol) =
        transferBalance(src.asSym(), trg.asSym(), amount.asSym())

    fun simplifySelfdestruct(c: TACCmd.EVM.SelfdestructCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        // self destruct is transfer of current balance to target, [TODO: reset extcodedata and extcodedatasize] and STOP
        val currentBalanceSymbol = TACKeyword.TMP(Tag.Bit256, "Balance")
        val loadBalance = simplifyBalance(TACCmd.EVM.AssignBalanceCmd(currentBalanceSymbol, address))
            .merge(currentBalanceSymbol, address)
        val transfer = transferBalance(src = address, trg = c.o, amount = currentBalanceSymbol)
        val stop = simplifyStop()

        return loadBalance
            .merge(transfer)
            .merge(stop)

    }

    // Call simplifications - generate a triple of lists of commands : if,then,else. If is responsible for putting the if condition inside the TAC COND symbol
    fun balanceCond(value: TACSymbol, target: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
        val currentBalanceSymbol = TACKeyword.TMP(Tag.Bit256, "Balance")
        val currentBalanceRead = simplifyBalance(TACCmd.EVM.AssignBalanceCmd(currentBalanceSymbol, address))
            .merge(currentBalanceSymbol, address)
        val checkCurrentBalanceSymbol = TACKeyword.COND.toVar().withSuffix("1")

        val checkCurrentBalanceCondLoad = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            checkCurrentBalanceSymbol,
            currentBalanceSymbol,
            value,
            TACExprFactUntyped::Ge
        )

        val otherBalanceSymbol = TACKeyword.TMP(Tag.Bit256, "OtherBalance")
        val otherBalanceRead = simplifyBalance(TACCmd.EVM.AssignBalanceCmd(otherBalanceSymbol, target))
            .merge(otherBalanceSymbol)
        val checkOtherBalanceSymbol = TACKeyword.COND.toVar().withSuffix("2")
        // no overflow, or address==target.
        // in ECF mode (and maybe everywhere in the future), there cannot be so much ether such that it overflows
        val checkOtherBalanceCondLoad =
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                checkOtherBalanceSymbol,
                TACExprFactSimple.Apply(
                    TACBuiltInFunction.NoAddOverflowCheck(Tag.Bit256).toTACFunctionSym(),
                    listOf(otherBalanceSymbol.asSym(), value.asSym()),
                    TACBuiltInFunction.NoAddOverflowCheck(Tag.Bit256).returnSort
                ).let { overflowCheckExp ->
                    if (BalanceCondCheckIfCallerIsCalleeForOverflow.get()) {
                        TACExprFactUntyped.LOr(
                            overflowCheckExp,
                            TACExprFactUntyped.Eq(address.asSym(), target.asSym())
                        )
                    } else {
                        overflowCheckExp
                    }
                }
            )
                .let { assignOverflowBalanceCond ->

                    listOf(assignOverflowBalanceCond)

                }

        val finalCond = TACKeyword.COND.toVar()
        val checkBalanceCondLoad = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            finalCond,
            checkCurrentBalanceSymbol,
            checkOtherBalanceSymbol,
            TACExpr.BinBoolOp::LAnd
        )

        return currentBalanceRead
            .merge(checkCurrentBalanceCondLoad)
            .merge(otherBalanceRead)
            .merge(checkOtherBalanceCondLoad)
            .merge(checkBalanceCondLoad, checkCurrentBalanceSymbol, checkOtherBalanceSymbol, finalCond)
    }

    private fun TACCmd.EVM.ExtCallCmdWithValue.getCallType() : TACCallType = when(this) {
        is TACCmd.EVM.CallCmd -> TACCallType.REGULAR_CALL
        is TACCmd.EVM.CallcodeCmd -> TACCallType.CALLCODE
    }

    private fun <T> simplifyCallWithValue(
        ptr: CmdPointer,
        c: T,
        graph: PatchingTACProgram<TACCmd>,
        withLhs: (T, TACSymbol.Var) -> T
    ) where T: TACCmd.EVM.ExtCallCmdWithValue, T : TACCmd.EVM {
        val mayPassValue = !isDefinitelyZero(c.value, ptr, graph)
        val outputHavocs = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(returnsize),
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(returndata),

        ), returnsize, returndata).merge(getRC())
        val callCore = CommandWithRequiredDecls(
                TACCmd.Simple.CallCore(
                to = c.addr,
                value = c.value,
                gas = c.gas,
                inSize = c.argsSize,
                inOffset = c.argsOffset,
                inBase = c.memBaseMap,

                outOffset = c.retOffset,
                outSize = c.retSize,
                outBase = c.memBaseMap,
                meta = c.meta,
                callType = c.getCallType()
            )
        ).withOpcodeSummary(
            withLhs(c, rc)
        )
        val rcLoad = CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = c.lhs,
                    rhs = rc.asSym()
                )
            ), rc
        )
        if(!mayPassValue) {
            val inst = outputHavocs.merge(
                callCore
            ).merge(
                rcLoad
            ).wrapWithCallAnnot()
            graph.replaceCommand(ptr, inst)
            return
        }

        val callerSym = TACKeyword.TMP(Tag.Bit256, "!Caller")
        val loadCaller = simplifyAddress(TACCmd.EVM.AssignAddressCmd(lhs = callerSym))
        val balanceConditionCheck = balanceCond(
            c.value, c.addr
        )
        val rejoinPoint = graph.splitBlockAfter(ptr)
        val endBlock = graph.addBlock(ptr.block, listOf(
            TACCmd.Simple.AnnotationCmd(TACMeta.CALL_GROUP_END),
            TACCmd.Simple.JumpCmd(rejoinPoint)
        ))
        val callBalanceFailBlock = applySummaryOnBalanceTransferFail(c, listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(rc, TACSymbol.Const(BigInteger.ZERO)),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(returnsize, TACSymbol.lift(0).asSym()),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(returndata, TACExprFactUntyped.resetStore(Tag.ByteMap)),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, rc),
        )).merge(TACCmd.Simple.JumpCmd(endBlock))

        val callBalanceFailTarget = graph.addBlock(ptr.block, callBalanceFailBlock)

        val repayBlock = repayLogic(
            mayPassValue = true,
            callerSymbol = callerSym,
            callValue = c.value,
            successIndicatorSymbol = rc,
            targetAddressSymbol = c.addr
        ).merge(TACCmd.Simple.JumpCmd(endBlock))

        val repayBlockId = graph.addBlock(ptr.block, repayBlock)

        val rcCheckVar = TACKeyword.TMP(Tag.Bool, "rcCheck")
        val rcCheckJump = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = rcCheckVar,
                rhs = TACExpr.BinRel.Eq(rc.asSym(), TACExpr.zeroExpr)
            ),
            TACCmd.Simple.JumpiCmd(cond = rcCheckVar, dst = repayBlockId, elseDst = endBlock)
        ), setOf(rc, rcCheckVar))
        // now the block that executes the call:
        // do call block
        val callBlockCommands = transferBalance(
            src = callerSym,
            trg = c.addr,
            amount = c.value
        ).merge(
            outputHavocs
        ).merge(
            callCore
        ).merge(
            rcLoad
        ).merge(
            rcCheckJump
        )

        val callBlockId = graph.addBlock(ptr.block, callBlockCommands)

        val callPrefixCmds = CommandWithRequiredDecls(
            TACCmd.Simple.AnnotationCmd(TACMeta.CALL_GROUP_START)
        ).merge(
            loadCaller
        ).merge(
            balanceConditionCheck
        ).merge(
            TACCmd.Simple.JumpiCmd(cond = TACKeyword.COND.toVar(), dst = callBlockId, elseDst = callBalanceFailTarget)
        )
        graph.replaceCommand(
            ptr, callPrefixCmds
        )
    }


    /**
     * In all call simplifications, it is up for the inliner to set up the return data
     * We are also going to assume that usually calls will either return 0 bytes or a C bytes where C is constant.
     * Therefore, no need to take the minimum between returnsize and outsize.
     * - because if returnsize < outsize then we're just copying unconstrained bytes so that is still sound
     * - if returnsize > outsize then we don't care since we should take just outsize bytes
     */
    private fun simplifyCall(ptr: CmdPointer, c: TACCmd.EVM.CallCmd, graph: PatchingTACProgram<TACCmd>) {
        simplifyCallWithValue<TACCmd.EVM.CallCmd>(ptr, c, graph) { cmd, lhs ->
            cmd.copy(lhs = lhs)
        }
    }

    private fun applySummaryOnBalanceTransferFail(c: TACCmd.EVM, callBalanceFailure: List<TACCmd.Simple.AssigningCmd.AssignExpCmd>): CommandWithRequiredDecls<TACCmd.Simple> {
        val (prefix, summ) = toOpcodeSummary(c, address) ?: (null to null)
        val cmds = (prefix ?: CommandWithRequiredDecls())
            .merge(callBalanceFailure).let {
                if (summ != null) {
                    it.merge(TACCmd.Simple.SummaryCmd(summ, MetaMap()))
                } else {
                    it
                }
            }

        return cmds
    }

    /**
     * if the simplifier may have passed value when simplifying the low level EVM call (saved in [mayPassValue]),
     * then transfer from the target [targetAddressSymbol] to caller [callerSymbol], an amount [callValue] that is equal to the passed value,
     * in case the success indicator ([successIndicatorSymbol]) is false (zero).
     * If the success indicator is true (non-zero), transfer an amount of 0 (no-op).
     */
    private fun repayLogic(
        mayPassValue: Boolean,
        callValue: TACSymbol,
        targetAddressSymbol: TACSymbol,
        callerSymbol: TACSymbol.Var,
        successIndicatorSymbol: TACSymbol.Var
    ) = if (mayPassValue) {
        val tmpRepayAmountSym = TACKeyword.TMP(Tag.Bit256, "repayAmount")
        CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                tmpRepayAmountSym,
                TACExpr.TernaryExp.Ite(
                    TACExpr.BinRel.Eq(successIndicatorSymbol.asSym(), TACSymbol.lift(0).asSym()),
                    callValue.asSym(),
                    TACSymbol.lift(0).asSym()
                )
            ),
            tmpRepayAmountSym
        ).merge(
            transferBalance(
                src = targetAddressSymbol,
                trg = callerSymbol,
                amount = tmpRepayAmountSym
            )
        )
    } else {
        CommandWithRequiredDecls<TACCmd.Simple>()
    }

    private fun simplifyCallcode(ptr: CmdPointer, c: TACCmd.EVM.CallcodeCmd, graph: PatchingTACProgram<TACCmd>) {
        val p : TACCmd.EVM.CallcodeCmd = c
        simplifyCallWithValue<TACCmd.EVM.CallcodeCmd>(ptr, p, graph) { cmd, lhs ->
            cmd.copy(lhs = lhs)
        }
    }

    private fun CommandWithRequiredDecls<TACCmd.Simple>.wrapWithCallAnnot() = CommandWithRequiredDecls(
        TACCmd.Simple.AnnotationCmd(TACMeta.CALL_GROUP_START)
    ).merge(
        this
    ).merge(TACCmd.Simple.AnnotationCmd(TACMeta.CALL_GROUP_END))

    fun simplifyDelegatecall(ptr: CmdPointer, c: TACCmd.EVM.DelegatecallCmd, patch: PatchingTACProgram<TACCmd>) {
        val havocReturnData =
            CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(returndata), returndata)
        val havocReturnsize =
            CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(returnsize), returnsize)
        val callcore = TACCmd.Simple.CallCore(
            to = c.addr,
            gas = c.gas,
            inOffset = c.argsOffset,
            inSize = c.argsSize,
            inBase = c.memBaseMap,
            outOffset = c.retOffset,
            outSize = c.retSize,
            outBase = c.memBaseMap,
            callType = TACCallType.DELEGATE,
            // current callvalue is what the delegatecall callee sees.
            // note there's no transfer instrumented! EVM semantics are so crazy
            value = callvalue,
            meta = c.meta
        )
        val havocRC = getRC()
        val rcLoad = TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, rc)
        val res = havocReturnData
            .merge(havocReturnsize)
            .merge(havocRC)
            .merge(
                CommandWithRequiredDecls(callcore, callvalue).withOpcodeSummary(c.copy(lhs=rc))
            )
            .merge(
                rcLoad
            ).wrapWithCallAnnot()
        patch.replaceCommand(ptr, res.cmds)
        patch.addRequiredDecls(res)
    }

    fun simplifyStaticcall(ptr: CmdPointer, c: TACCmd.EVM.StaticcallCmd, patch: PatchingTACProgram<TACCmd>) {
        val havocReturnData =
            CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(returndata), returndata)
        val havocReturnsize =
            CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(returnsize), returnsize)
        val callcore = TACCmd.Simple.CallCore(
            to = c.addr,
            gas = c.gas,
            inOffset = c.argsOffset,
            inSize = c.argsSize,
            inBase = c.memBaseMap,
            outOffset = c.retOffset,
            outSize = c.retSize,
            outBase = c.memBaseMap,
            callType = TACCallType.STATIC,
            value = TACSymbol.Const(BigInteger.ZERO),
            meta = c.meta
        )
        val havocRC = getRC()
        val rcLoad = TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, rc)
        val res = havocReturnData
            .merge(havocReturnsize)
            .merge(havocRC)
            .merge(
                CommandWithRequiredDecls(callcore).withOpcodeSummary(c.copy(lhs=rc))
            )
            .merge(rcLoad).wrapWithCallAnnot()
        patch.replaceCommand(ptr, res.cmds)
        patch.addRequiredDecls(res)
    }

    private fun <T> generateCreateCollisionCheck(
        tgtAddress: TACSymbol.Var,
        cb: (checkCommands: CommandWithRequiredDecls<TACCmd.Simple>, noCollision: TACSymbol.Var) -> T
    ): T {
        val currNonce = TACKeyword.TMP(Tag.Bit256, "!currNonce")
        val currCodesize = TACKeyword.TMP(Tag.Bit256, "!currCodesize")
        val readPrefix = CommandWithRequiredDecls(
            listOfNotNull(
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = currNonce,
                    base = nonce,
                    loc = tgtAddress
                ).takeIf { Config.UseNonceForCollisionChecks.get() },
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = currCodesize,
                    base = extcodesize,
                    loc = tgtAddress
                )
            ), setOf(currCodesize, tgtAddress, extcodesize, nonce, currNonce)
        )
        val unfold = ExprUnfolder.unfoldToSingleVar("!existingCheck", with(TACExprFactUntyped) {
            Eq(currCodesize.asSym(), TACExpr.zeroExpr).letIf(Config.UseNonceForCollisionChecks.get()) {
                LAnd(it, Eq(currNonce.asSym(), TACExpr.zeroExpr))
            }
        })
        val checkPrefix = readPrefix.merge(unfold.cmds).merge(*unfold.newVars.toTypedArray())
        return cb(checkPrefix, unfold.e.s as TACSymbol.Var)
    }

    /**
     * Generates Simple TAC commands for the EVM CREATE operation (not to be confused with create2).
     *
     * To generate the returned address, we first increase the nonce by one, then return hash(caller address, caller's nonce).
     * The hash is guaranteed not to collide with any other contract address, including:
     * 1. The current contract
     * 2. Other contracts in a multi-contract setting
     * 3. precompiled contracts
     * 4. The address zero
     * 5. Other addresses previously returned by the hash
     *
     * It is possible to send money to the newly generated contract if its constructor is payable.
     */
    private fun simplifyCreate(ptr: CmdPointer, c: TACCmd.EVM.CreateCmd, patch: PatchingTACProgram<TACCmd>) {
        commonSimplifyCreate(ptr, c = c, useAssume = Config.AssumeNoCreateCollision, patch = patch) { newContractAddress, callerSymbol, nonceSymbol ->
            CommandWithRequiredDecls<TACCmd.Simple>(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    newContractAddress,
                    TACExpr.SimpleHash(
                        (EVM_WORD_SIZE_INT * 2).asTACExpr,
                        listOf(callerSymbol.asSym(), nonceSymbol.asSym()),
                        hashFamily = HashFamily.Create(resultSizeInBytes = MAX_GENERATED_ADDRESS_BITWIDTH_inBytes)
                    ),
                    meta = MetaMap(TACMeta.CONTRACT_CREATION to Allocator.getFreshId(Allocator.Id.CONTRACT_CREATION))
                ),
                newContractAddress
            )
        }
    }

    private fun conditionalCreate(
        ptr: CmdPointer,
        c: TACCmd.EVM.AnyCreateCmd,
        patch: PatchingTACProgram<TACCmd>,
        setupCode: CommandWithRequiredDecls<TACCmd.Simple>,
        newAddress: TACSymbol.Var,
        finishCode: CommandWithRequiredDecls<TACCmd.Simple>
    ) {
        val joinPoint = patch.splitBlockAfter(ptr)
        val elseBlockBody = CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = c.lhs, TACExpr.zeroExpr)
        ), c.lhs).withOpcodeSummary(
            c as TACCmd.EVM
        ).merge(TACCmd.Simple.JumpCmd(joinPoint))
        val elseBlock = patch.addBlock(
            ptr.block, elseBlockBody
        )
        val thenBlock = patch.addBlock(ptr.block, finishCode.merge(TACCmd.Simple.JumpCmd(joinPoint)))
        val condCode = generateCreateCollisionCheck(newAddress) { cmds, noCollision ->
            (setupCode andThen cmds).merge(
                TACCmd.Simple.JumpiCmd(
                    cond = noCollision, dst = thenBlock, elseDst = elseBlock
                )
            )
        }
        patch.replaceCommand(p = ptr, condCode)

    }

    private data class CreateSetup(
        val code: CommandWithRequiredDecls<TACCmd.Simple>,
        val newAddressSym: TACSymbol.Var,
        val callerSym: TACSymbol.Var
    )

    private fun commonCreateSetup(
        getNewAddress: (lhs: TACSymbol.Var, caller: TACSymbol.Var, nonce: TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ): CreateSetup {
        val callerSymbol = TACKeyword.TMP(Tag.Bit256, "Caller")
        val caller = simplifyAddress(TACCmd.EVM.AssignAddressCmd(callerSymbol)).merge(callerSymbol)

        // Nonce usage and incrementation
        val nonceSymbol = TACKeyword.TMP(Tag.Bit256, "Nonce")
        val nonceReadCmd = CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.WordLoad(
                lhs = nonceSymbol,
                loc = callerSymbol,
                base = nonce
            ), nonceSymbol, nonce
        )
        val nonceIncSym = TACKeyword.TMP(Tag.Bit256, "incrementedNonce")
        val nonceIncCmd = CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                nonceIncSym,
                TACExpr.Vec.Add(nonceSymbol.asSym(), BigInteger.ONE.asTACSymbol().asSym())
            ), nonceIncSym
        )
        val nonceStoreCmd = CommandWithRequiredDecls(
            TACCmd.Simple.WordStore(callerSymbol, nonceIncSym, nonce),
            callerSymbol, nonceIncSym, nonce
        )

        val newContractAddress = TACKeyword.CREATED.toVar().withMeta(TACMeta.IS_CREATED_ADDRESS)
        val computeNewContractAddress = getNewAddress(newContractAddress, callerSymbol, nonceSymbol)
        val newContractAddressIsAnAddress = ExprUnfolder.unfoldPlusOneCmd("createdInBound", with(TACExprFactUntyped) {
            LAnd(
                Lt(TACExpr.zeroExpr, newContractAddress.asSym()),
                Le(newContractAddress.asSym(), EVMConfig.maxAddress.asTACExpr)
            )
        }) { e ->
            TACCmd.Simple.AssumeCmd(e.s, "newContractAddressIsAnAddress")
        }
        return CreateSetup(
            CommandWithRequiredDecls.mergeMany(
                caller,
                nonceReadCmd,
                nonceIncCmd,
                nonceStoreCmd,
                computeNewContractAddress,
                newContractAddressIsAnAddress
            ), newContractAddress, callerSymbol
        )
    }

    /**
     * Take care of things in common to create and create2: transfer, getGas, return data and repay
     */
    private fun finishSimplifyCreate(
        lhs: TACSymbol.Var,
        value: TACSymbol,
        offset: TACSymbol,
        length: TACSymbol,
        memBaseMap: TACSymbol.Var,
        meta: MetaMap,
        callerSymbol: TACSymbol.Var,
        newContractAddress: TACSymbol.Var,
        orig: TACCmd.EVM.AnyCreateCmd
    ): CommandWithRequiredDecls<TACCmd.Simple> {

        val transfer = transferBalance(src = callerSymbol, trg = newContractAddress, amount = value)
        val gas = TACKeyword.TMP(Tag.Bit256, "Gas")
        val getGas = CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignGasCmd(gas), gas)
        val callcore = TACCmd.Simple.CallCore(
            to = newContractAddress,
            gas = gas,
            inOffset = offset,
            inSize = length,
            inBase = memBaseMap,
            outOffset = TACSymbol.Const(BigInteger.ZERO),
            outSize = TACSymbol.Const(BigInteger.ZERO),
            outBase = memBaseMap,
            callType = TACCallType.CREATE,
            value = value,
            meta = meta
        )
        // rc is the new address.
        // TODO: Note that to correctly model failure, we should havoc it again and require it to be either 0 or the new address (otherwise we transfer some amount to 0)
        val returnAssign = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            TACKeyword.CONSTRUCTOR_RETURN.toVar(),
            rhs = newContractAddress.asSym()
        )
        val rcLoad = TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, TACKeyword.CONSTRUCTOR_RETURN.toVar())
        val havocReturnData =
            CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignHavocCmd(returndata), returndata)
        val nullReturnSize = CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(returnsize, TACSymbol.lift(0)),
            returnsize
        )
        val setCreatedAccountData = run {
            val sizeTmp = TACKeyword.TMP(Tag.Bit256, "nonZeroCodeSize")
            val existingSize = TACKeyword.TMP(Tag.Bit256, "existingCodeSize")
            CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = existingSize,
                    base = extcodesize,
                    loc = newContractAddress
                ),
                existingSize, extcodesize, newContractAddress, sizeTmp
            ).merge(
                ExprUnfolder.unfoldPlusOneCmd(
                    "codeSize",
                    TACExprFactSimple.Lt(TACExpr.zeroExpr, sizeTmp.asSym(), Tag.Bool)
                ) {
                    TACCmd.Simple.AssumeCmd(it.s, "nonZeroCodeSize")
                }
            ).merge(
                ExprUnfolder.unfoldPlusOneCmd("codeSize", with(TACExprFactUntyped) {
                    Ite(
                        Eq(lhs.asSym(), TACExpr.zeroExpr),
                        TACExpr.zeroExpr,
                        Ite(
                            Gt(existingSize.asSym(), TACExpr.zeroExpr),
                            existingSize.asSym(),
                            sizeTmp.asSym()
                        )
                    )
                }) {
                    TACCmd.Simple.WordStore(
                        base = EthereumVariables.extcodesize, loc = newContractAddress, value = it.s
                    )
                }
            ).merge(ExprUnfolder.unfoldPlusOneCmd("nonce", with(TACExprFactUntyped) {
                Ite(
                    Eq(lhs.asSym(), TACExpr.zeroExpr),
                    TACExpr.zeroExpr,
                    1.asTACExpr
                )
            }) {
                TACCmd.Simple.WordStore(
                    base = nonce,
                    loc = newContractAddress,
                    value = it.s
                )
            })
        }
        /*
         * NB: we do *not* revert the nonce of the *caller* if the creation reverted (unlike the extcodesize change here).
         * This is the behavior specified by the yellow paper, which specifies that immediately on create an "irreversible"
         * change to the global state occurs, i.e., nonce. Note that if the method call which *contains* the create
         * invocation itself reverts, then we do indeed rollback the nonce changes.
         */
        return transfer
            .merge(getGas)
            .merge(havocReturnData)
            .merge(nullReturnSize)
            .merge(
                CommandWithRequiredDecls(
                    listOf(returnAssign),
                    setOf(gas, TACKeyword.CONSTRUCTOR_RETURN.toVar())
                )
            )
            .merge(
                CommandWithRequiredDecls(callcore).withOpcodeSummary(orig.updateLhs(newContractAddress))
            )
            .merge(
                rcLoad
            )
            .merge(setCreatedAccountData)
            .merge(repayLogic(true, value, newContractAddress, callerSymbol, lhs))
    }

    private fun commonSimplifyCreate(
        ptr: CmdPointer,
        c: TACCmd.EVM.AnyCreateCmd,
        useAssume: ConfigType<Boolean>,
        patch: PatchingTACProgram<TACCmd>,
        getNewAddress: (lhs: TACSymbol.Var, caller: TACSymbol.Var, nonce: TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ) {
        val (setupCode, newAddress, callerSymbol) = commonCreateSetup(getNewAddress)
        val finish = finishSimplifyCreate(c.lhs, c.value, c.argsOffset, c.argsSize, c.memBaseMap, c.meta, callerSymbol, newAddress, c)
        if (useAssume.get()) {
            val assumeNoCollision = generateCreateCollisionCheck(newAddress) { chk, noCollision ->
                chk.merge(TACCmd.Simple.AssumeCmd(noCollision, "assumeNoCollision"))
            }
            val toReplace = setupCode andThen assumeNoCollision andThen finish
            patch.replaceCommand(ptr, toReplace.cmds)
            patch.addVarDecls(toReplace.varDecls)
        } else {
            conditionalCreate(
                ptr,
                c = c,
                patch = patch,
                newAddress = newAddress,
                setupCode = setupCode,
                finishCode = finish
            )
        }

    }

    /**
     * Incorporate the salt, the caller and the contract's constructor to create a new address
     * and pass this address in the meta key CREATE2_COMPUTED_ADDRESS
     *
     */
    private fun simplifyCreate2(ptr: CmdPointer, c: TACCmd.EVM.Create2Cmd, patch: PatchingTACProgram<TACCmd>) {
        commonSimplifyCreate(ptr, patch = patch, c = c, useAssume = Config.AssumeNoCreate2Collision) { lhs: TACSymbol.Var, callerSymbol: TACSymbol.Var, _: TACSymbol.Var ->
            // Create a hash of the constructor's memory map to pass to the create2 function
            val tmpInitCodeHash = TACKeyword.TMP(Tag.Bit256, "InitCodeHash")
            val memBaseMapHashCmd = CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                    lhs = tmpInitCodeHash,
                    memBaseMap = c.memBaseMap,
                    op1 = c.offset,
                    op2 = c.length
                ),
                tmpInitCodeHash
            )
            val tmpDummy = TACKeyword.TMP(Tag.Bit256, "DummyVar")
            val packedEncodeForHash = TACKeyword.TMP(Tag.ByteMap, "PackedEncodeForHash")
            val noPaddingAddress = TACKeyword.TMP(Tag.Bit256, "NoPaddingAddress")
            val computeAddress =
                CommandWithRequiredDecls(
                    listOf(
                        /**
                         * simulate the hashing of the buffer created by
                         * `abi.encodePacked(bytes1(0xff), owner address ,salt, initcode hash)`
                         * No need to actually create this buffer.
                         *
                         * details:
                         * When a packed buffer is hashed, the different TACSymbols comprising it are
                         * hashed together with two constants that represent the length of the buffer
                         * and the bit locations of the relevant parts in each TACSymbol.
                         * This magic is handled by DisciplinedHashModel.
                         * DisciplinedHashModel does this when hashing buffers created in code.
                         * Since this buffer is created on the fly, DisciplinedHashModel shouldn't, and
                         * probably cannot handle it. So we just simulate it ourselves, and since the
                         * length of the buffer and its different part are known in advance, we just
                         * stick the magic numbers that would have been created by DisciplinedHashModel.
                         * Hence the 0x55 and the 0x11535 constants
                         */
                        TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                            lhs = lhs,
                            length = 0x55.asTACSymbol(),
                            args = listOf(
                                BigInteger.valueOf(0x11535).asTACSymbol(), // sized of the packed variables. encoded
                                BigInteger.valueOf(0xff).asTACSymbol(),
                                callerSymbol,
                                c.salt,
                                tmpInitCodeHash

                            ),
                            meta = MetaMap(TACMeta.CONTRACT_CREATION to Allocator.getFreshId(Allocator.Id.CONTRACT_CREATION)).plus(TACMeta.CREATE2_COMPUTATION)
                        )
                    ),
                    setOf(packedEncodeForHash, noPaddingAddress, tmpInitCodeHash, tmpDummy, lhs)
                )
            memBaseMapHashCmd andThen computeAddress
        }
    }

    fun simplifyLog(c: TACCmd.EVM.LogCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        val (args, meta) = when (c) {
            is TACCmd.EVM.EVMLog0Cmd -> listOf(c.o1,c.o2) to c.meta
            is TACCmd.EVM.EVMLog1Cmd -> listOf(c.o1,c.o2,c.t1) to c.meta
            is TACCmd.EVM.EVMLog2Cmd -> listOf(c.o1,c.o2,c.t1,c.t2) to c.meta
            is TACCmd.EVM.EVMLog3Cmd -> listOf(c.o1,c.o2,c.t1,c.t2,c.t3) to c.meta
            is TACCmd.EVM.EVMLog4Cmd -> listOf(c.o1,c.o2,c.t1,c.t2,c.t3,c.t4) to c.meta
        }
        return CommandWithRequiredDecls(
            TACCmd.Simple.LogCmd(args, meta=meta)
        )

    }

    fun setLastReverted(lastReverted: Boolean, meta: MetaMap = MetaMap()): CommandWithRequiredDecls<TACCmd.Simple> {
        val hasRevertedSymbol = CVLKeywords.lastReverted.toVar(Tag.Bool)

        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(hasRevertedSymbol, lastReverted.asTACSymbol(), meta),
                TACCmd.Simple.AnnotationCmd(
                    if (lastReverted) {
                        REVERT_PATH
                    } else {
                        RETURN_PATH
                    }
                )
            ),
            setOf(hasRevertedSymbol)
        )
    }

    fun simplifyInvalid(c: TACCmd.EVM.InvalidCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        unused(c) // reason why we're keeping this?
        return setLastReverted(lastReverted = true).merge(
            listOf(
                TACCmd.Simple.RevertCmd(
                    TACSymbol.Const(BigInteger.ZERO),
                    TACSymbol.Const(BigInteger.ZERO),
                    TACRevertType.THROW,
                    memory
                )
            )
        )
    }

    fun simplifyRevert(
        c: TACCmd.EVM.EVMRevertCmd
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return setLastReverted(lastReverted = true).merge(
            listOf(
                TACCmd.Simple.RevertCmd(c.component1(), c.component2(), c.component3(), c.component4(), c.component5())
            )
        )
    }

    fun simplifyReturn(
        c: TACCmd.EVM.EVMReturnCmd
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return setLastReverted(lastReverted = false, meta = c.meta).merge(
            listOf(
                TACCmd.Simple.ReturnCmd(c.component1(), c.component2(), c.component3(), c.component4())
            )
        )
    }

    fun simplifyMload(c: TACCmd.EVM.MloadCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        if (Mem0x0To0x40AsScalar && c.loc is TACSymbol.Const && c.loc.value in setOf(
                BigInteger.valueOf(0),
                BigInteger.valueOf(32)
            )
        ) {
            return when (c.loc.value) {
                BigInteger.valueOf(0) -> CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        c.lhs,
                        TACKeyword.MEM0.toVar()
                    ),
                    TACKeyword.MEM0.toVar()
                )

                BigInteger.valueOf(32) -> CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        c.lhs,
                        TACKeyword.MEM32.toVar()
                    ),
                    TACKeyword.MEM32.toVar()
                )

                else -> throw AssertionError("Cannot handle $c location as a reserved location scalar")
            }
        }

        return CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.ByteLoad(
                c.lhs,
                c.loc,
                c.memBaseMap as TACSymbol.Var,
                c.meta
            )
        )
    }

    fun simplifyMstore(c: TACCmd.EVM.MstoreCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        if (Mem0x0To0x40AsScalar && c.loc is TACSymbol.Const && c.loc.value in setOf(
                BigInteger.valueOf(0),
                BigInteger.valueOf(32)
            )
        ) {
            return when (c.loc.value) {
                BigInteger.valueOf(0) -> CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        TACKeyword.MEM0.toVar(), c.rhs
                    ),
                    TACKeyword.MEM0.toVar()
                )

                BigInteger.valueOf(32) -> CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        TACKeyword.MEM32.toVar(), c.rhs
                    ),
                    TACKeyword.MEM32.toVar()
                )

                else -> throw AssertionError("Cannot handle $c location as a reserved location scalar")
            }
        }

        return CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.ByteStore(
                c.loc,
                c.rhs,
                c.memBaseMap,
                c.meta
            )
        )
    }

    fun simplifyMbytestore(c: TACCmd.EVM.MbytestoreCmd) =
        CommandWithRequiredDecls<TACCmd.Simple>(
            TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                c.loc,
                c.rhs,
                c.memBaseMap,
                c.meta
            )
        )


    fun simplifySload(c: TACCmd.EVM.SloadCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.AssigningCmd.WordLoad(
            c.lhs,
            c.loc,
            c.storageBaseMap as TACSymbol.Var,
            c.meta + IS_STORAGE_ACCESS
        )
    )

    fun simplifySstore(c: TACCmd.EVM.SstoreCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.WordStore(
            c.loc,
            c.rhs,
            c.storageBaseMap,
            c.meta + IS_STORAGE_ACCESS
        )
    )

    fun simplifyTload(c: TACCmd.EVM.TloadCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.AssigningCmd.WordLoad(
            c.lhs,
            c.loc,
            c.transientStorageBaseMap as TACSymbol.Var,
            c.meta + IS_TRANSIENT_STORAGE_ACCESS
        )
    )

    fun simplifyTstore(c: TACCmd.EVM.TstoreCmd) = CommandWithRequiredDecls<TACCmd.Simple>(
        TACCmd.Simple.WordStore(
            c.loc,
            c.rhs,
            c.transientStorageBaseMap,
            c.meta + IS_TRANSIENT_STORAGE_ACCESS
        )
    )

    fun simplifyStop(): CommandWithRequiredDecls<TACCmd.Simple> {
        return setLastReverted(lastReverted = false).merge(
            listOf(
                TACCmd.Simple.ReturnCmd(TACSymbol.Const(BigInteger.ZERO), TACSymbol.Const(BigInteger.ZERO))
            )
        )
    }

    /**
     * Generates code that backs up arguments and a summary for the opcode,
     * sandwiching the original simplification between the prefix (opcode argument saved) and the actual summary that should
     * be an after-summary.
     */
    private fun CommandWithRequiredDecls<TACCmd.Simple>.withOpcodeSummary(c: TACCmd.EVM) : CommandWithRequiredDecls<TACCmd.Simple> {
        val (prefix, summ) = toOpcodeSummary(c, address) ?: return this

        val commands = listOfNotNull(
            TACCmd.Simple.SummaryCmd(summ, MetaMap()),
            if (summ is BalanceOpcodeSummary) {
                SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet(summ.output, summ.addr).toAnnotation()
            } else {
                null
            }
        )

        val summary = CommandWithRequiredDecls(commands, summ.variables)
        return when (c) {
            // halting instructions should first put the summary and only then the simplification
            is TACCmd.EVM.EVMRevertCmd, is TACCmd.EVM.SelfdestructCmd, is TACCmd.EVM.InvalidCmd -> {
                (prefix?.merge(summary) ?: summary).merge(this)
            }
            else -> {
                this.merge(summary).let {
                    prefix?.merge(it) ?: it
                }
            }
        }

    }

    private fun PatchingTACProgram<TACCmd>.patchOpcodeSummary(c: LTACCmd) {
        val (prefix, summ) = toOpcodeSummary(c.cmd, address) ?: return
        this.insertAfter(c.ptr,listOf(
            TACCmd.Simple.SummaryCmd(
                summ, MetaMap()
            )
        ))
        // this is always null, but future proofing
        if(prefix != null) {
            this.addBefore(c.ptr, prefix.cmds)
            this.addVarDecls(prefix.varDecls)
        }
    }

    private fun simplifyCommand(c: TACCmd.EVM): CommandWithRequiredDecls<TACCmd.Simple> {
        return when (c) {
            is TACCmd.EVM.CallOrCreateCmd -> error("Call simplifications for $c should not go through this path")

            is TACCmd.EVM.LogCmd -> simplifyLog(c)

            is TACCmd.EVM.InvalidCmd -> simplifyInvalid(c)
            is TACCmd.EVM.EVMRevertCmd -> simplifyRevert(c)
            is TACCmd.EVM.EVMReturnCmd -> simplifyReturn(c)

            is TACCmd.EVM.MloadCmd -> simplifyMload(c)
            is TACCmd.EVM.MstoreCmd -> simplifyMstore(c)
            is TACCmd.EVM.MbytestoreCmd -> simplifyMbytestore(c)
            is TACCmd.EVM.McopyCmd -> simplifyMcopy(c)

            is TACCmd.EVM.SloadCmd -> simplifySload(c)
            is TACCmd.EVM.SstoreCmd -> simplifySstore(c)
            is TACCmd.EVM.TloadCmd -> simplifyTload(c)
            is TACCmd.EVM.TstoreCmd -> simplifyTstore(c)

            is TACCmd.EVM.AssignCalldatasizeCmd -> simplifyCalldatasize(c)
            is TACCmd.EVM.AssignCalldataloadCmd -> simplifyCalldataload(c)
            is TACCmd.EVM.CalldatacopyCmd -> simplifyCalldatacopy(c)

            is TACCmd.EVM.ReturndatasizeCmd -> simplifyReturnsize(c)
            is TACCmd.EVM.ReturndatacopyCmd -> simplifyReturndatacopy(c)

            TACCmd.EVM.StopCmd -> simplifyStop()
            is TACCmd.EVM.AssignIszeroCmd -> simplifyIsZero(c)

            is TACCmd.EVM.AssignAddressCmd -> simplifyAddress(c)
            is TACCmd.EVM.AssignBalanceCmd -> simplifyBalance(c)
            is TACCmd.EVM.AssignOriginCmd -> simplifyOrigin(c)
            is TACCmd.EVM.AssignCallerCmd -> simplifyCaller(c)
            is TACCmd.EVM.AssignCallvalueCmd -> simplifyCallvalue(c)
            is TACCmd.EVM.AssignGaspriceCmd -> simplifyGasprice(c)
            is TACCmd.EVM.AssignBlockhashCmd -> simplifyBlockhash(c)
            is TACCmd.EVM.AssignCoinbaseCmd -> simplifyCoinbase(c)
            is TACCmd.EVM.AssignTimestampCmd -> simplifyTimestamp(c)
            is TACCmd.EVM.AssignNumberCmd -> simplifyNumber(c)
            is TACCmd.EVM.AssignDifficultyCmd -> simplifyDifficulty(c)
            is TACCmd.EVM.AssignGaslimitCmd -> simplifyGaslimit(c)
            is TACCmd.EVM.AssignChainidCmd -> simplifyChainid(c)
            is TACCmd.EVM.AssignSelfBalanceCmd -> simplifySelfBalance(c)
            is TACCmd.EVM.AssignBasefeeCmd -> simplifyBasefee(c)
            is TACCmd.EVM.AssignBlobhashCmd -> simplifyBlobhash(c)
            is TACCmd.EVM.AssignBlobbasefeeCmd -> simplifyBlobbasefee(c)

            is TACCmd.EVM.AssignCodesizeCmd -> simplifyCodesize(c)
            is TACCmd.EVM.CodecopyCmd -> simplifyCodecopy(c)
            is TACCmd.EVM.AssignExtcodesizeCmd -> simplifyExtcodesize(c)
            is TACCmd.EVM.ExtcodecopyCmd -> simplifyExtcodecopy(c)
            is TACCmd.EVM.AssignExtcodehashCmd -> simplifyExtcodehash(c)

            is TACCmd.EVM.SelfdestructCmd -> simplifySelfdestruct(c)
            is TACCmd.EVM.AssigningCmd.AssignByteCmd -> simplifyAssignByteCmd(c)
            is TACCmd.EVM.AssigningCmd.WithExprBuilder<*> -> simplifyAssigningCmdWithExprBuilder(c)
            is TACCmd.EVM.AssigningCmd.AssignLibraryAddressCmd -> simplifyLibraryLookupCommand(c)
        }.withOpcodeSummary(c)
    }

    private fun simplifyLibraryLookupCommand(c: TACCmd.EVM.AssigningCmd.AssignLibraryAddressCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = c.lhs,
                    rhs = TACExpr.Apply(
                        TACBuiltInFunction.LinkContractAddress,
                        listOf(c.libraryContractId.asTACExpr),
                        Tag.Bit256
                    )
                )
            ), c.lhs
        )
    }

    /**
     * Returns a list of [TACCmd.Simple.AssigningCmd.AssignExpCmd] commands
     * that encode [c].
     * These commands encode the command '[c].lhs := ([c].op2 >>l (248 - [c].op1 * 8)) & 0xFF' in a "TAC" form, i.e.,
     * where each subexpression is unfolded (i.e., assigned to a separate [TACSymbol.Var]),
     * unless it is a symbol or a constant.
     */
    fun simplifyAssignByteCmd(c: TACCmd.EVM.AssigningCmd.AssignByteCmd): CommandWithRequiredDecls<TACCmd.Simple> {
        val simpleCmds = mutableListOf<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
        val tmpVar = TACKeyword.TMP(Tag.Bit256)
        val rightShiftFactor =
            when (c.op1) {
                is TACSymbol.Var -> {
                    tmpVar
                }

                is TACSymbol.Const -> {
                    val cVal = c.op1.value
                    check(cVal <= 31.toBigInteger() && cVal >= BigInteger.ZERO) {
                        "Expected that $c.op1 would be a constant in [0..31] @ $c"
                    }
                    TACSymbol.lift(248 - 8 * cVal.intValueExact())
                }
            }
        if (rightShiftFactor is TACSymbol.Var) {
            val assignOp1TimesEight = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                tmpVar,
                TACExprFactUntyped.Mul(
                    TACExprFactUntyped.Sym(TACSymbol.Const(BigInteger.valueOf(8))),
                    c.op1.asSym()
                )
            )
            simpleCmds.add(assignOp1TimesEight)
            val assign248MinusOp1TimesEight = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                tmpVar,
                TACExprFactUntyped.Sub(
                    TACSymbol.lift(248).asSym(),
                    tmpVar.asSym()
                )
            )
            simpleCmds.add(assign248MinusOp1TimesEight)
        }
        val assignRightShift = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            tmpVar,
            TACExprFactUntyped.ShiftRightLogical(
                c.op2.asSym(),
                rightShiftFactor.asSym()
            )
        )
        simpleCmds.add(assignRightShift)
        val assign8LSBitsMask = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            c.lhs,
            TACExprFactUntyped.BWAnd(
                tmpVar.asSym(),
                TACExprFactSimple.Sym(TACSymbol.Const(BigInteger.valueOf(255)))
            )
        )
        simpleCmds.add(assign8LSBitsMask)
        return CommandWithRequiredDecls<TACCmd.Simple>(
            simpleCmds.toList(), tmpVar
        )
    }

    fun simplifyAssigningCmdWithExprBuilder(c: TACCmd.EVM.AssigningCmd.WithExprBuilder<*>): CommandWithRequiredDecls<TACCmd.Simple> =
        CommandWithRequiredDecls<TACCmd.Simple>(TACCmd.Simple.AssigningCmd.AssignExpCmd(c.lhs, c.rhsAsExpr(), c.meta))

    // Convert some complicated TAC commands to simple assignments
    fun simplify(code: EVMTACProgram): CoreTACProgram {
        logger.info { "Simplifying EVMTACProgram ${code.name}" }
        val codeWithoutEmptyBlocks = removeEmptyBlocks(code)

        val patchingProgram = codeWithoutEmptyBlocks.toPatchingProgram()

        patchingProgram.forEachOriginal { ptr, cmd ->
            when (cmd) {
                is TACCmd.Simple -> {
                    // we may want to attach opcode summaries for some of the Simple commands
                    patchingProgram.patchOpcodeSummary(LTACCmd(ptr, cmd))
                }
                // start by handling call commands with value passing that require a more sophisticated replacement
                is TACCmd.EVM.CallCmd -> {
                    simplifyCall(ptr, cmd, patchingProgram)
                }

                is TACCmd.EVM.CallcodeCmd -> {
                    simplifyCallcode(ptr, cmd, patchingProgram)
                }
                is TACCmd.EVM.DelegatecallCmd -> simplifyDelegatecall(ptr, cmd, patchingProgram)
                is TACCmd.EVM.StaticcallCmd -> simplifyStaticcall(ptr, cmd, patchingProgram)
                is TACCmd.EVM.CreateCmd -> simplifyCreate(ptr, cmd, patchingProgram)
                is TACCmd.EVM.Create2Cmd -> simplifyCreate2(ptr, cmd, patchingProgram)

                is TACCmd.EVM -> {
                    val simplifiedCmdsWithReqDecls = simplifyCommand(cmd)
                    if (simplifiedCmdsWithReqDecls.cmds.isEmpty()) {
                        throw Exception("Must not simplify a list to an empty list")
                    }
                    patchingProgram.replaceCommand(ptr, simplifiedCmdsWithReqDecls.cmds)
                    patchingProgram.addRequiredDecls(simplifiedCmdsWithReqDecls)
                }

                is TACCmd.CVL -> throw IllegalStateException("Unexpected to see a CVL TACCmd $cmd")
                is TACCmd.Move -> throw IllegalStateException("Unexpected to see a Move TACCmd $cmd")
            }
        }

        patchingProgram.addVarDecl(memory) // always added
        patchingProgram.addVarDecl(balance) // always added

        val newCode = patchingProgram.toCode(code)

        logger.info { "Had ${code.code.keys.size} blocks, now has ${newCode.code.keys.size}" }
        logger.debug { "Original graph ${code.blockgraph}" }
        logger.debug { "New graph ${newCode.blockgraph}" }
        logger.debug { "Same graph? ${code.blockgraph == newCode.blockgraph}" }


        ArtifactManagerFactory().dumpCodeArtifacts(newCode, ReportTypes.SIMPLIFIED, DumpTime.POST_TRANSFORM)
        val newCmds = convertAsSimple(newCode.code)
        val newProg = CoreTACProgram(
            newCmds,
            newCode.blockgraph,
            newCode.name,
            newCode.symbolTable,
            IProcedural.empty(),
            check = false // will not type check due to bool/int collisions
        )
        twoStateInvariant(code, newProg, ReportTypes.SIMPLIFIED)
        return newProg
    }

}

/**
 * a best-effort def analysis of symbol v's definition from point ptr, considering the original graph
 */
private fun isDefinitelyZero(v: TACSymbol, ptr: CmdPointer, graph: PatchingTACProgram<TACCmd>): Boolean {
    if (v is TACSymbol.Const && v.value == BigInteger.ZERO) return true

    val pred = graph.origBlockGraphReversed[ptr.block]?.singleOrNull()
    val lookIn = graph.originalCode[ptr.block]!!.subList(0, ptr.pos).let { current ->
        if (pred != null && pred in graph.originalCode) {
            graph.originalCode[pred]!! + current
        } else {
            current
        }
    }
    val firstWrite = lookIn.reversed().firstMapped { it.takeIf { it.getLhs() == v } }
    return if (firstWrite == null) {
        false
    } else {
        (firstWrite is TACCmd.Simple.AssigningCmd.AssignExpCmd && firstWrite.rhs.getAsConst() == BigInteger.ZERO) ||
            (firstWrite is TACCmd.EVM.AssigningCmd.WithExprBuilder.AssignSymCmd && firstWrite.rhs is TACSymbol.Const && firstWrite.rhs.value == BigInteger.ZERO)
    }
}

fun convertAsSimple(c: BlockNodes<TACCmd>): BlockNodes<TACCmd.Simple> {
    return c.mapValues { l ->
        l.value.map { it as? TACCmd.Simple ?: throw IllegalStateException("Command $it in ${l.key} has not been simplified") }
    }
}
