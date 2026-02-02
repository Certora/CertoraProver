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

@file:UseSerializers(BigIntegerSerializer::class)
package instrumentation.calls

import allocator.GenerateRemapper
import datastructures.stdcollections.*
import analysis.*
import analysis.EthereumVariables.rc
import analysis.EthereumVariables.returndata
import analysis.EthereumVariables.returnsize
import analysis.icfg.CallGraphBuilder
import analysis.icfg.CallInput
import analysis.icfg.ExpressionSimplifier
import config.Config
import datastructures.stdcollections.setOfNotNull
import kotlinx.serialization.UseSerializers
import scene.ITACMethod
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACSymbol.Companion.Zero
import vc.data.TACSymbol.Companion.atSync
import java.io.Serializable
import java.math.BigInteger

/**
 * Apply longstore as needed, and simplify for simple cases (0/32 bytes write)
 */
private fun longStore(srcBase: TACSymbol.Var, srcOffset: TACSymbol, dstBase: TACSymbol.Var, dstOffset: TACSymbol, size: TACExpr): CommandWithRequiredDecls<TACCmd.Simple> {
    // long copy
    /*
     * It is vitally important that all returndata copying is done via longstores in equivalence checking mode
     */
    if (size !is TACExpr.Sym.Const || Config.EquivalenceCheck.get()) {
        return longStoreAssignment(dstBase, dstOffset, srcBase, srcOffset, size)
    }
    val sz = size.s.value
    if (sz.mod(32.toBigInteger()) != BigInteger.ZERO) {
        return longStoreAssignment(dstBase, dstOffset, srcBase, srcOffset, size)
    }
    val numWords = sz.div(32.toBigInteger()).intValueExact() // if sz is huge, something definitely went wrong

    if (numWords == 0) {
        return CommandWithRequiredDecls()
    }
    val commandsToRet = mutableListOf<TACCmd.Simple>()
    val decls = mutableSetOf<TACSymbol.Var>()

    // start by first "0th" word (we really want to avoid simplifying dstOffset etc, will ruin double inilning heuristics)
    storeKwordOfLongstore(
        commandsToRet,
        decls,
        dstOffset.asSym(),
        srcOffset.asSym(),
        srcBase,
        dstBase
    )

    // go from 1 to numWords
    (1 until numWords).forEach { k ->
        storeKwordOfLongstore(
            commandsToRet,
            decls,
            TACExpr.Vec.Add(dstOffset.asSym(), TACSymbol.lift(k*32).asSym()),
            TACExpr.Vec.Add(srcOffset.asSym(), TACSymbol.lift(k*32).asSym()),
            srcBase,
            dstBase
        )
    }
    return CommandWithRequiredDecls(commandsToRet, decls)
}

private fun storeKwordOfLongstore(
    commandsToRet: MutableList<TACCmd.Simple>, // out
    decls: MutableSet<TACSymbol.Var>, // out
    dstOffset: TACExpr,
    srcOffset: TACExpr,
    srcBase: TACSymbol.Var,
    dstBase: TACSymbol.Var
): CommandWithRequiredDecls<TACCmd.Simple> {
    // if we don't have to convert the expression to a symbol, let's not add the command
    // this helps the dumb scratchMemResolveHeuristic
    val retTmp = TACKeyword.TMP(Tag.Bit256, "ret")
    decls.add(retTmp)

    val retDstOffsetTmp = if (dstOffset !is TACExpr.Sym.Var) {
        val t = TACKeyword.TMP(Tag.Bit256, "retDstOffset")
        commandsToRet.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                t,
                dstOffset
            )
        )
        decls.add(t)
        t
    } else {
        dstOffset.s
    }

    // ditto with srcOffset
    val retSrcOffsetTmp = if (srcOffset !is TACExpr.Sym.Var) {
        val t = TACKeyword.TMP(Tag.Bit256, "retSrcOffset")
        commandsToRet.add(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                t,
                srcOffset
            )
        )
        decls.add(t)
        t
    } else {
        srcOffset.s
    }

    commandsToRet.add(
        TACCmd.Simple.AssigningCmd.ByteLoad(
            retTmp,
            retSrcOffsetTmp,
            srcBase
        )
    )
    commandsToRet.add(
        TACCmd.Simple.AssigningCmd.ByteStore(
            retDstOffsetTmp,
            retTmp,
            dstBase
        )
    )
    return CommandWithRequiredDecls(
        commandsToRet,
        decls
    )
}

private fun longStoreAssignment(
    dstBase: TACSymbol.Var,
    dstOffset: TACSymbol,
    srcBase: TACSymbol.Var,
    srcOffset: TACSymbol,
    size: TACExpr
): CommandWithRequiredDecls<TACCmd.Simple> {
    return CommandWithRequiredDecls(
        TACCmd.Simple.AssigningCmd.AssignExpCmd(
            dstBase,
            TACExpr.LongStore(
                dstBase.asSym(),
                dstOffset.asSym(),
                srcBase.asSym(),
                srcOffset.asSym(),
                size
            )
        )
    )
}

@KSerializable
data class CallOutput(val base: TACSymbol.Var, val offset: TACSymbol, val size: TACSymbol) : Serializable, TransformableVarEntity<CallOutput> {

    fun feedFrom(srcBase: TACSymbol.Var, srcOffset: TACSymbol, srcLength: TACSymbol) = longStore(
            srcBase,
            srcOffset = srcOffset,
            dstBase = base,
            dstOffset = offset,
            size = if(srcLength is TACSymbol.Const && size is TACSymbol.Const) {
                srcLength.value.min(size.value).asTACSymbol().asSym()
            } else {
                TACExpr.TernaryExp.Ite(
                        TACExpr.BinRel.Lt(srcLength.asSym(), size.asSym()),
                        srcLength.asSym(),
                        size.asSym()
                )
            }
    ).let {
        @Suppress("DEPRECATION")
        CommandWithRequiredDecls(it.cmds, TACUtils.tagsFromList(it.cmds))
    }

    fun feedFrom(tacSymbol: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
        return if(size is TACSymbol.Const && size.value >= 32.toBigInteger()) {
            CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                            base = base,
                            loc = offset,
                            value = tacSymbol
                    )
            ), setOfNotNull(base, offset as? TACSymbol.Var, tacSymbol as? TACSymbol.Var))
        } else {
            val ind = TACKeyword.TMP(Tag.Bit256, "ind")
            val havoc = TACKeyword.TMP(Tag.Bit256, "chaos")
            val value = TACKeyword.TMP(Tag.Bit256, "value")
            CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                            lhs = havoc
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = ind,
                            rhs = offset
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = value,
                            rhs = TACExpr.TernaryExp.Ite(
                                    i = TACExpr.BinRel.Gt(
                                            size.asSym(),
                                            31.toBigInteger().asTACSymbol().asSym()
                                    ),
                                    t = tacSymbol.asSym(),
                                    e = havoc.asSym()
                            )
                    ),
                    TACCmd.Simple.AssigningCmd.ByteStore(
                            base = base,
                            loc = ind,
                            value = value
                    )
            ), setOfNotNull(ind, havoc, value, base, (offset as? TACSymbol.Var), size as? TACSymbol.Var, tacSymbol as? TACSymbol.Var))
        }
    }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CallOutput {
        fun TACSymbol.f() = when(this) {
            is TACSymbol.Const -> this
            is TACSymbol.Var -> f(this)
        }
        return CallOutput(
            base = f(base),
            offset = offset.f(),
            size = size.f()
        )
    }
}

/**
 * Represents information about the *caller* calling convention. That is, what arguments are we passing, what is the
 * base map where it resides, etc.
 *
 * The information about how our *callee* expects their data is extracted from the [ITACMethod.calldataEncoding] class
 * in the argument to [setup].
 */
@GenerateRemapper
@KSerializable
data class CallConvention(val input: CallInput, val rawOut: CallOutput) : Serializable, TransformableVarEntity<CallConvention> {

    fun remapCalledContracts(f: (CallGraphBuilder.CalledContract) -> CallGraphBuilder.CalledContract?) : CallConvention {
        return CallConvention(
            input.remapContractReference(f),
            rawOut
        )
    }

    companion object {

        fun addK(e: TACExpr, k: BigInteger) =
            when {
                k == BigInteger.ZERO -> e
                e is TACExpr.Sym.Const -> TACExpr.Sym(TACSymbol.lift(e.s.value + k))
                else -> TACExpr.Vec.Add(
                    e,
                    k.asTACSymbol().asSym()
                )
            }

    }

    val callerId = ((input.baseVar as TACExpr.Sym).s as TACSymbol.Var).callIndex

    private fun updateReturndata(srcBase: TACSymbol.Var, offset: TACSymbol, size: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
        val returndata = returndata.atSync(callIndex = callerId)
        val returnsize = returnsize.at(callIndex = callerId)
        val decls = mutableSetOf(srcBase, returndata, returnsize)
        if (size is TACSymbol.Var) {
            decls.add(size)
        }
        if (offset is TACSymbol.Var) {
            decls.add(offset)
        }
        return CommandWithRequiredDecls(
            listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(returnsize, size)
            ),
            decls
        ).merge(
            longStore(
                srcBase,
                offset,
                returndata,
                Zero,
                size.asSym()
            )
        )
    }

    private fun updateReturndata(tacSymbol: TACSymbol) : CommandWithRequiredDecls<TACCmd.Simple> =
        returndata.atSync(callIndex = callerId).let { returnBase ->
            returnsize.at(callIndex = callerId).let { returnSizeSym ->
                CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = returnSizeSym,
                                rhs = 32.toBigInteger().asTACSymbol().asSym()
                        ),
                        TACCmd.Simple.AssigningCmd.ByteStore(
                                base = returnBase,
                                value = tacSymbol,
                                loc = BigInteger.ZERO.asTACSymbol()
                        )
                ), setOfNotNull(returnBase, returnSizeSym, tacSymbol as? TACSymbol.Var))
            }
        }

    fun setup(method: ITACMethod): ITACMethod {
        val calleeId = (method.code as CoreTACProgram).entryBlockId.getCallId()
        val calldataEncWithCalleeId = (method.calldataEncoding as CalldataEncoding).copyWithCallId(calleeId)
        val updated = (method.code as CoreTACProgram).prependToBlock0(calldataEncWithCalleeId.feedInput(input, method)).let {
            this.handleOut(it)
        }
        return method.shallowForkWithCodeAndCalldataEncoding(updated, calldataEncWithCalleeId)
    }

    fun handleOut(toInst: CoreTACProgram): CoreTACProgram {
        val rc = rc.at(callIndex = callerId)
        val rcTo1 = CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = rc,
                    rhs = TACSymbol.lift(1)
                )
            ), setOf(rc)
        )
        val rcTo0 = CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = rc,
                    rhs = TACSymbol.lift(0)
                )
            ), setOf(rc)
        )

        return toInst.patching {
                patching ->
            val sinks = this.getEndingBlocks()
            val simplifier = ExpressionSimplifier(g = this.analysisCache.graph)
            for(s in sinks) {
                var skipSinceNoRet = false
                val retLtac = this.code[s]!!.mapIndexedNotNull { ind, c ->
                    when(c) {
                        is TACCmd.Simple.RevertCmd,
                        is TACCmd.Simple.ReturnSymCmd,
                        is TACCmd.Simple.ReturnCmd -> {
                            LTACCmd(ptr = CmdPointer(s, ind), cmd = c)
                        }
                        else -> null
                    }
                }.let { rets ->
                    if (rets.isNotEmpty()) {
                        rets.singleOrNull()
                            ?: throw IllegalStateException("Found sink with multiple return instructions: $rets")
                    } else {
                        skipSinceNoRet = true
                        null
                    }
                }

                if (skipSinceNoRet) {
                    continue
                }

                val retCmd = retLtac!!.cmd
                val toAdd = when(retCmd) {
                    is TACCmd.Simple.ReturnCmd -> {
                        val offset = simplifier.simplify(retCmd.o1.asSym(), ptr = retLtac.ptr, inPrestate = true).getAs<TACExpr.Sym>()?.s ?: retCmd.o1
                        val size = simplifier.simplify(retCmd.o2.asSym(), ptr = retLtac.ptr, inPrestate = true).getAs<TACExpr.Sym>()?.s ?: retCmd.o2
                        rawOut.feedFrom(retCmd.memBaseMap, offset, size).merge(
                            updateReturndata(retCmd.memBaseMap, offset, size)
                        ).merge(
                            rcTo1
                        )
                    }
                    is TACCmd.Simple.RevertCmd -> {
                        // we must set returndata too. doing a best effort thing
                        val offset = simplifier.simplify(retCmd.o1.asSym(), ptr = retLtac.ptr, inPrestate = true).getAs<TACExpr.Sym>()?.s ?: retCmd.o1
                        val size = simplifier.simplify(retCmd.o2.asSym(), ptr = retLtac.ptr, inPrestate = true).getAs<TACExpr.Sym>()?.s ?: retCmd.o2

                        // tacM0x0 case....
                        if (size is TACSymbol.Const && size.value == BigInteger.valueOf(32) &&
                            offset is TACSymbol.Const && offset.value == BigInteger.ZERO) {

                            updateReturndata(TACKeyword.MEM0.toVar(callIndex=retCmd.base.callIndex))
                        } else {
                            // general case, but I suspect this method does not account for tacM0x0 and tacM0x20
                            updateReturndata(retCmd.base, offset, size)
                        }.merge(
                            rcTo0
                        )
                    }
                    is TACCmd.Simple.ReturnSymCmd -> {
                        updateReturndata(retCmd.o).merge(
                            rawOut.feedFrom(retCmd.o)
                        ).merge(
                            rcTo1
                        )
                    }
                    else -> error("impossible case")
                }
                patching.addBefore(retLtac.ptr, toAdd.cmds)
                patching.addRequiredDecls(toAdd)
            }
        }
    }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CallConvention {
        return CallConvention(
            input = this.input.transformSymbols(f),
            rawOut = this.rawOut.transformSymbols(f)
        )
    }
}

fun convertReturnsToSummaries(p: CVLTACProgram): CVLTACProgram {
    val g = p.commandGraph
    val patch = p.toPatchingProgram()
    g.commands
        .filterIsInstance<GenericLTACCmd<TACCmd.Simple>>()
        .filter { it.cmd is TACCmd.Simple.ReturnCmd || it.cmd is TACCmd.Simple.ReturnSymCmd || it.cmd is TACCmd.Simple.RevertCmd }
        .forEach { lc ->
            patch.replace(
                lc.ptr
            ) { _ -> listOf(TACCmd.Simple.SummaryCmd(ReturnSummary(lc.cmd), MetaMap())) }
        }
    return patch.toCode(p)
}

fun convertReturnsToSummaries(p: CoreTACProgram): CoreTACProgram {
    val g = p.analysisCache.graph
    val patch = p.toPatchingProgram()
    g.commands
        .filter { it.cmd is TACCmd.Simple.ReturnCmd || it.cmd is TACCmd.Simple.ReturnSymCmd || it.cmd is TACCmd.Simple.RevertCmd }
        .forEach { lc ->
            patch.replace(
                lc.ptr
            ) { _ -> listOf(TACCmd.Simple.SummaryCmd(ReturnSummary(lc.cmd), MetaMap())) }
        }
    return patch.toCode(p)
}
