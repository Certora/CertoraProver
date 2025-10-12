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

package wasm.impCfg

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.opt.DiamondSimplifier.registerDestructivelyMergeableAnnot
import analysis.opt.DiamondSimplifier.registerMergeableAnnot
import com.certora.collect.*
import datastructures.stdcollections.*
import utils.Range
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import wasm.cfg.PC
import wasm.impCfg.StraightLine.Local
import wasm.impCfg.WasmImpInstr.createVarNameHelper
import wasm.impCfg.WasmNumericExpr.reconstructExpr
import wasm.impCfg.WasmNumericExpr.transformTmps
import wasm.ir.*
import wasm.summarization.WasmCallSummarizer
import wasm.tokens.WasmTokens.GLOBAL
import wasm.tokens.WasmTokens.HAVOC
import wasm.tokens.WasmTokens.HAVOC_VAR_NM
import wasm.tokens.WasmTokens.LOCAL
import wasm.tokens.WasmTokens.MEMORY_COPY
import wasm.tokens.WasmTokens.MEMORY_FILL
import wasm.tokens.WasmTokens.UNDERSCORE
import wasm.tokens.WasmTokens.WASMICFG
import wasm.tokens.WasmTokens.WASMICFG_CALL
import wasm.tokens.WasmTokens.WASMICFG_CALL_INDIRECT
import wasm.tokens.WasmTokens.WASMICFG_IF
import wasm.tokens.WasmTokens.WASMICFG_INTERNAL_UNREACH
import wasm.tokens.WasmTokens.WASMICFG_JUMP
import wasm.tokens.WasmTokens.WASMICFG_RET
import wasm.tokens.WasmTokens.WASMICFG_SWITCH
import wasm.tokens.WasmTokens.WASMICFG_UNREACH
import java.math.BigInteger


class CannotHaveHavocException(msg: String) : Exception(msg)
class AlphaRenamingControlCmdFailed(msg: String) : Exception(msg)
class UnexpectedBrTab(msg: String) : Exception(msg)
class UnexpectedCallIndirect(msg: String) : Exception(msg)


typealias WasmToTacInfo = CommandWithRequiredDecls<TACCmd.Simple>

val WASM_INLINED_FUNC_START = MetaKey<StraightLine.InlinedFuncStartAnnotation.TAC>("wasm.inline.start").registerDestructivelyMergeableAnnot()
val WASM_INLINED_FUNC_END = MetaKey<StraightLine.InlinedFuncEndAnnotation.TAC>("wasm.inline.end").registerDestructivelyMergeableAnnot()
val WASM_SDK_FUNC_SUMMARY_START = MetaKey<StraightLine.InlinedFuncStartAnnotation.TAC>("wasm.sdk.func.start").registerDestructivelyMergeableAnnot()
val WASM_SDK_FUNC_SUMMARY_END = MetaKey<StraightLine.InlinedFuncEndAnnotation.TAC>("wasm.sdk.func.end").registerDestructivelyMergeableAnnot()
val WASM_HOST_FUNC_SUMMARY_START = MetaKey<StraightLine.InlinedFuncStartAnnotation.TAC>("wasm.host.func.start").registerDestructivelyMergeableAnnot()
val WASM_HOST_FUNC_SUMMARY_END = MetaKey<StraightLine.InlinedFuncEndAnnotation.TAC>("wasm.host.func.end").registerDestructivelyMergeableAnnot()
val WASM_USER_ASSUME = MetaKey<String>("wasm.user.assume").registerMergeableAnnot()
val WASM_USER_ASSERT = MetaKey<String>("wasm.user.assert").registerMergeableAnnot()
val WASM_MEMORY_OP_WIDTH = MetaKey<Int>("wasm.memory.op.width")

//meta that contains information about the original address of the bytecode instruction in the .wasm file
val WASM_BYTECODE_ADDRESS = MetaKey<WASMAddress>("wasm.bytecode.address")

//the actual local idx in the original .wasm bytecode
val WASM_LOCAL_IDX = MetaKey<WASMLocalIdx>("wasm.bytecode.local.idx")

//for memory store instructions, the offset into the memory
val WASM_MEMORY_OFFSET = MetaKey<WASMMemoryOffset>("wasm.bytecode.memory.offset")

val WASM_UNRESOLVED_CALL = MetaKey<WasmName>("wasm.unresolved.call")

private fun assignHavocPrimitive(sym: TACSymbol.Var, type: WasmPrimitiveType) =
    mergeMany(
        assignHavoc(sym),
        assume {
            sym.asSym() lt when (type) {
                WasmPrimitiveType.I32, WasmPrimitiveType.F32 -> BigInteger.TWO.pow(32)
                WasmPrimitiveType.I64, WasmPrimitiveType.F64 -> BigInteger.TWO.pow(64)
            }.asTACExpr
        }
    )

class WasmImpCfgContext(
    val summarizer: WasmCallSummarizer,
) {
    private var fresh = 0
    fun allocFresh() = fresh++

    private var satisfyCount = 0
    fun allocSatisfyId() = satisfyCount++
}

/**
 * Interface for WasmImpCfg which is what we generate from the Wasm CFG and then
 * convert to WasmTAC which then gets converted to Certora's TAC.
 * WasmImpCfg is a very simple, imperative language.
 * There are two interfaces that extend WasmTACCmd, one for control instruction and one for
 * straight line ones.
 *
 * */
sealed class WasmImpCfgCmd(open val addr: WASMAddress? = null) {

    /**
     * Returns a list of TAC.Simple commands for each Wasm TAC command, a set
     * of all defined tac symbols in the command, and updates a fresh variable id counter.
     * The set of symbols is needed for the CoreTacProgram's symbol table.
     * */
    context(WasmImpCfgContext)
    fun wasmImpcfgToTacSimple() = wasmImpcfgToTacSimpleInternal().let {
        it.copy(cmds = it.cmds.map { c -> addr?.let { c.plusMeta(WASM_BYTECODE_ADDRESS, it) } ?: c })
    }

    context(WasmImpCfgContext)
    abstract fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo
}

sealed class Control(addr: WASMAddress? = null) : WasmImpCfgCmd(addr) {
    abstract fun succs(): List<PC>

    data class Jump(val pc: PC, override val addr: WASMAddress? = null) : Control(addr) {
        override fun succs(): List<PC> {
            return listOf(pc)
        }

        override fun toString(): String {
            return "$WASMICFG_JUMP -> $pc"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val dest = WasmImpCfgToTAC.mkCvtBlockId(pc)
            return WasmToTacInfo(listOf(TACCmd.Simple.JumpCmd(dest)), setOf())
        }
    }

    data class Brif(val arg: Arg, val ifpc: PC, val elpc: PC, override val addr: WASMAddress? = null) : Control(addr) {
        override fun succs(): List<PC> {
            return listOf(ifpc, elpc)
        }

        override fun toString(): String {
            return "$WASMICFG_IF ($arg) -> [$ifpc, $elpc]"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val destIf = WasmImpCfgToTAC.mkCvtBlockId(ifpc)
            val destElse = WasmImpCfgToTAC.mkCvtBlockId(elpc)
            val brCmpResult = TACKeyword.TMP(Tag.Bool, "$arg!negated")

            return WasmToTacInfo.mergeMany(
                // argVar := !arg
                assign(brCmpResult) { arg.toTacExpr() eq TACExpr.zeroExpr },

                // If and Else are "flipped" because we check for (arg == 0), i.e. (!arg)
                WasmToTacInfo(TACCmd.Simple.JumpiCmd(destElse, destIf, brCmpResult))
            )
        }
    }

    data class BrTable(val arg: Arg, val dests: List<PC>, val fallBack: PC, override val addr: WASMAddress? = null) :
        Control(addr) {
        override fun succs(): List<PC> {
            return dests + fallBack
        }

        override fun toString(): String {
            return "$WASMICFG_SWITCH ($arg) -> ${dests + fallBack}"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            throw UnexpectedBrTab("This should never happen, BrTabs are converted to BrIfs early on.")
        }
    }

    data class Ret(val arg: Arg?, override val addr: WASMAddress? = null) : Control(addr) {
        override fun succs(): List<PC> {
            return listOf()
        }

        override fun toString(): String {
            return if (this.arg == null) {
                WASMICFG_RET
            } else {
                "$WASMICFG_RET $arg"
            }
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            if (arg != null) {
                val argSym = arg.toTacSymbol()
                return WasmToTacInfo(listOf(TACCmd.Simple.ReturnSymCmd(argSym)), setOf())
            } else {
                return WasmToTacInfo(listOf(TACCmd.Simple.ReturnSymCmd(TACSymbol.Zero)), setOf())
            }
        }
    }

    data class CertoraUnreachable(
        override val addr: WASMAddress? = null,
        val isAssert: Boolean = true,
        val message: String = ""
    ) : Control(addr) {
        override fun succs(): List<PC> {
            return listOf()
        }

        override fun toString(): String {
            return WASMICFG_INTERNAL_UNREACH
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            if (isAssert) {
                return listOf(TACCmd.Simple.AssertCmd(false.asTACSymbol(), message)).withDecls()
            } else {
                return listOf(TACCmd.Simple.AssumeCmd(false.asTACSymbol(), message)).withDecls()
            }
        }
    }

    /**
     * Unreachable is the same as Assert(False)
     */
    data class Unreach(override val addr: WASMAddress? = null, val isTrap: Boolean = true) : Control(addr) {
        override fun succs(): List<PC> {
            return listOf()
        }

        override fun toString(): String {
            return WASMICFG_UNREACH
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            return Trap.trap("unreachable")
        }
    }

    // TODO: CERT-5549 CallIndirect, etc.

}

sealed class StraightLine(addr: WASMAddress? = null) : WasmImpCfgCmd(addr) {
    /**
     * Checks if the rhs can be a "havoc" or "top".
     * This is used for converting these havoc expressions to a straight line instruction
     * `WasmIcfgHavoc` from which it will be easier to go to CVT TAC's `AssignHavocCmd`.
     * */
    abstract fun hasHavoc(): Boolean

    /**
     * Generates new havoced variable names.
     * This also returns the non-havoced arguments as is,
     * for helping with reconstruction.
     * */
    abstract fun getVarsForHavoc(allocFresh: () -> Int): List<Arg>

    /** Returns a set of all the pcs that appear in the `Tmp` register variables in this instruction.
     */
    abstract val tempIdxs: Set<Int>

    /**
     * Assign to the lhs (a register) the WasmIcfgExpr `expr`.
     * */
    data class SetTmp(val lhs: Tmp, val expr: WasmIcfgExpr, override val addr: WASMAddress? = null) :
        StraightLine(addr) {
        override fun hasHavoc(): Boolean {
            return this.expr.hasHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            val havocedVarNms = mutableListOf<Arg>()
            if (this.hasHavoc()) {
                for ((i, arg) in this.expr.getArgs().withIndex()) {
                    if (arg.isHavoc()) {
                        havocedVarNms.add(ArgRegister(Tmp(arg.type, allocFresh(), HAVOC_VAR_NM + i, 0)))
                    } else {
                        havocedVarNms.add(arg)
                    }
                }
            }
            return havocedVarNms
        }

        override val tempIdxs = setOf(lhs.pc)

        override fun toString(): String {
            return "$lhs := $expr"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val tacExpr = expr.toTacExpr()
            val lhsSym = TACSymbol.Var(lhs.toString(), tacExpr.tag!!)
            return assign(lhsSym) {
                tacExpr
            }
        }
    }

    data class Local(val n: WASMLocalIdx, val callIdx: Int) {
        override fun toString(): String {
            return "$LOCAL$UNDERSCORE${n}_$callIdx"
        }
    }

    data class SetLocal(val local: Local, val arg: Arg, override val addr: WASMAddress? = null) : StraightLine(addr) {
        override fun toString(): String {
            return "$local := $arg"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val lhsName = local.toString()
            val lhs = TACSymbol.Var(lhsName, Tag.Bit256).withMeta(WASM_LOCAL_IDX, local.n)
            return WasmToTacInfo(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, arg.toTacSymbol())),
                setOf(lhs)
            )
        }

        override fun hasHavoc(): Boolean {
            return this.arg.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return if (this.hasHavoc()) {
                listOf(ArgRegister(Tmp(arg.type, allocFresh(), "${HAVOC_VAR_NM}_$local", 0)))
            } else {
                listOf(arg)
            }
        }

        override val tempIdxs = setOf<Int>()
    }

    data class GetLocal(val lhs: Tmp, val local: Local, override val addr: WASMAddress? = null) : StraightLine(addr) {
        override fun toString(): String {
            return "$lhs := $local"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val lhsSym = TACSymbol.Var(lhs.toString(), Tag.Bit256)
            val value = TACSymbol.Var(local.toString(), Tag.Bit256).withMeta(WASM_LOCAL_IDX, local.n)
            return WasmToTacInfo(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhsSym, value)),
                setOf(lhsSym)
            )
        }

        override fun hasHavoc(): Boolean {
            return false
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return emptyList()
        }

        override val tempIdxs = setOf(lhs.pc)
    }

    data class SetGlobal(val nm: String, val arg: Arg, override val addr: WASMAddress? = null) : StraightLine(addr) {
        override fun toString(): String {
            return "$GLOBAL$UNDERSCORE$nm := $arg"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val lhsName = createVarNameHelper(GLOBAL, nm)
            val lhs = TACSymbol.Var(lhsName, Tag.Bit256)
            return WasmToTacInfo(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, arg.toTacSymbol())),
                setOf(lhs)
            )
        }

        override fun hasHavoc(): Boolean {
            return this.arg.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return if (this.hasHavoc()) {
                listOf(ArgRegister(Tmp(arg.type, allocFresh(), HAVOC_VAR_NM + nm, 0)))
            } else {
                listOf(arg)
            }
        }

        override val tempIdxs = setOf<Int>()
    }

    data class GetGlobal(val lhs: Tmp, val int: String, override val addr: WASMAddress? = null) : StraightLine(addr) {
        override fun toString(): String {
            return "$lhs := $GLOBAL$UNDERSCORE$int"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val value = TACSymbol.Var(createVarNameHelper(GLOBAL, int), Tag.Bit256)
            val lhsSym = TACSymbol.Var(lhs.toString(), Tag.Bit256)
            return WasmToTacInfo(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(lhsSym, value)),
                setOf(lhsSym)
            )
        }

        override fun hasHavoc(): Boolean {
            return false
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return emptyList()
        }

        override val tempIdxs = setOf(lhs.pc)
    }

    data class Store(
        val at: Arg,
        val op: StoreMemoryOp,
        val offset: WASMMemoryOffset,
        val value: Arg,
        val numBytes: Int? = 0,
        override val addr: WASMAddress? = null
    ) : StraightLine(addr) {
        override fun toString(): String {
            return if (numBytes == 0) {
                "$WASMICFG$op[$at] := $value"
            } else {
                "$WASMICFG$op[$at]{$numBytes} := $value"
            }
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val loc = at.toTacSymbol()
            val value = value.toTacSymbol()
            val tacCmd = TACCmd.Simple.AssigningCmd.ByteStore(
                loc,
                value,
                TACKeyword.MEMORY.toVar(),
                MetaMap(WASM_MEMORY_OP_WIDTH to op.widthInBytes) + (WASM_MEMORY_OFFSET to offset)
            )
            return WasmToTacInfo(tacCmd)
        }

        override fun hasHavoc(): Boolean {
            return this.value.isHavoc() || this.at.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            if (!this.hasHavoc()) {
                return emptyList()
            }

            val args = listOf(
                Pair(at, at.type),
                Pair(value, value.type)
            )

            val havocedArgs = args.map { ( arg, typ) ->
                if (arg.isHavoc()) {
                    val name = listOf(arg.toString(), HAVOC_VAR_NM).joinToString(UNDERSCORE)
                    ArgRegister(Tmp(typ, allocFresh(), name, 0))
                } else {
                    arg
                }
            }

            return havocedArgs
        }

        override val tempIdxs = setOf<Int>()
    }

    // Need to also support alignment, but not sure if it makes any difference.
    data class Load(
        val type: WasmPrimitiveType,
        val to: Tmp,
        val op: LoadMemoryOp,
        val from: Arg,
        val num: Int? = 0,
        override val addr: WASMAddress? = null
    ) : StraightLine(addr) {
        override fun toString(): String {
            return if (num == 0) {
                "$to := $WASMICFG$op $from"
            } else {
                "$to := $WASMICFG$op ($from) {$num}"
            }
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val lhs = TACSymbol.Var(to.toString(), Tag.Bit256)
            val loc = from.toTacSymbol()
            val rawVal = TACKeyword.TMP(Tag.Bit256)
            return mergeMany(
                listOf(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        rawVal,
                        loc,
                        TACKeyword.MEMORY.toVar(),
                        MetaMap(WASM_MEMORY_OP_WIDTH to op.widthInBytes)
                    )
                ).withDecls(rawVal),
                // Narrow the result to the operation's bit width
                assign(lhs) {
                    rawVal.asSym().mod(BigInteger.TWO.pow(op.widthInBytes * 8).asTACExpr)
                }
            )
        }

        override fun hasHavoc(): Boolean {
            return this.from.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return if (this.hasHavoc()) {
                listOf(
                    ArgRegister(
                        Tmp(
                            type,
                            allocFresh(),
                            listOf(to.toString(), HAVOC_VAR_NM).joinToString(UNDERSCORE),
                            0
                        )
                    )
                )
            } else {
                listOf(from)
            }
        }

        override val tempIdxs = setOf(to.pc)
    }

    data class Copy(val dest: Arg, val source: Arg, val numBytes: Arg, override val addr: WASMAddress? = null): StraightLine(addr) {
        override fun toString(): String {
            return "$WASMICFG$MEMORY_COPY ($dest $source $numBytes)"
        }

        override fun hasHavoc(): Boolean {
            return this.dest.isHavoc() || this.source.isHavoc() || this.numBytes.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            if (!this.hasHavoc()) {
                return emptyList()
            }

            val args = listOf(
                Pair(dest, dest.type),
                Pair(source, source.type),
                Pair(numBytes, numBytes.type)
            )

            val havocedArgs = args.map { ( arg, typ) ->
                if (arg.isHavoc()) {
                    val name = listOf(arg.toString(), HAVOC_VAR_NM).joinToString(UNDERSCORE)
                    ArgRegister(Tmp(typ, allocFresh(), name, 0))
                } else {
                    arg
                }
            }

            return havocedArgs
        }

        override val tempIdxs = setOf<Int>()

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val destSym = dest.toTacSymbol()
            val srcSym = source.toTacSymbol()
            val numBytesSym = numBytes.toTacSymbol()

            return mergeMany(
                // assume indices are within range
                assume {
                    (destSym.asSym() add numBytesSym.asSym() lt BigInteger.TWO.pow(32).asTACExpr) and
                        (srcSym.asSym() add numBytesSym.asSym() lt BigInteger.TWO.pow(32).asTACExpr)
                },
                WasmMemoryCopy(dstOffset = destSym, srcOffset = srcSym, length = numBytesSym).toCmd()
            )
        }

        // adapted from our memcpy summary, but no ret since memory.copy does not return anything
        @KSerializable
        @Treapable
        private data class WasmMemoryCopy(
            val dstOffset: TACSymbol,
            val srcOffset: TACSymbol,
            val length: TACSymbol,
        ) : ITESummary() {
            override val inputs get() = listOf(dstOffset, srcOffset, length)
            override val trueWriteVars get() = setOf(TACKeyword.MEMORY.toVar())
            override val falseWriteVars get() = setOf(TACKeyword.MEMORY.toVar())
            override fun transformSymbols(f: Transformer) =
                WasmMemoryCopy(
                    dstOffset = f(dstOffset),
                    srcOffset = f(srcOffset),
                    length = f(length)
                )

            // if (non-overlapping or identical)
            override val cond get() = TACExprFactUntyped {
                (dstOffset.asSym() eq srcOffset.asSym()) or
                    ((dstOffset.asSym() add length.asSym()) le srcOffset.asSym()) or
                    ((srcOffset.asSym() add length.asSym()) le dstOffset.asSym())
            }

            // then mem[dst:dst+len] := mem[src:src+len]
            override fun onTrue() =
                    TACCmd.Simple.ByteLongCopy(
                        dstOffset = dstOffset,
                        srcOffset = srcOffset,
                        length = length,
                        dstBase = TACKeyword.MEMORY.toVar(),
                        srcBase = TACKeyword.MEMORY.toVar(),
                        meta = MetaMap(LONG_COPY_STRIDE to 1)
                    ).withDecls()

            // else copy values to a temporary buffer and then to mem[dst:dst+len]
            override fun onFalse() =
                letBuf(
                    fromByteMap = TACKeyword.MEMORY.toVar(),
                    fromPos = { srcOffset.toTACExpr() },
                    len = { length.toTACExpr() },
                    stride = 1,
                ) { buf ->
                    TACCmd.Simple.ByteLongCopy(
                        dstOffset = dstOffset,
                        srcOffset = TACSymbol.Zero,
                        length = length,
                        dstBase = TACKeyword.MEMORY.toVar(),
                        srcBase = buf.s,
                        meta = MetaMap(LONG_COPY_STRIDE to 1)
                    ).withDecls()
                }
        }

    }


    data class Fill(val memPtr: Arg, val value: Arg, val numBytes: Arg, override val addr: WASMAddress? = null): StraightLine(addr) {
        override fun toString(): String {
            return "$WASMICFG$MEMORY_FILL ($memPtr $value $numBytes)"
        }
        override fun hasHavoc(): Boolean {
            return this.memPtr.isHavoc() || this.value.isHavoc() || this.numBytes.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            if (!this.hasHavoc()) {
                return emptyList()
            }

            val args = listOf(
                Pair(memPtr, memPtr.type),
                Pair(value, value.type),
                Pair(numBytes, numBytes.type)
            )

            val havocedArgs = args.map { ( arg, typ) ->
                if (arg.isHavoc()) {
                    val name = listOf(arg.toString(), HAVOC_VAR_NM).joinToString(UNDERSCORE)
                    ArgRegister(Tmp(typ, allocFresh(), name, 0))
                } else {
                    arg
                }
            }

            return havocedArgs
        }

        override val tempIdxs = setOf<Int>()

        // Similar to memset builtin
        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val memPtrSym = memPtr.toTacSymbol()
            val valueSym = value.toTacSymbol()
            val numBytesSym = numBytes.toTacSymbol()
            val initBuf = TACKeyword.TMP(Tag.ByteMap, "memset")
            return mergeMany(
                defineMap(initBuf) { _ ->
                    valueSym.asSym()
                },
                CommandWithRequiredDecls(
                    TACCmd.Simple.ByteLongCopy(
                        srcBase = initBuf,
                        srcOffset = TACSymbol.Zero,
                        dstBase = TACKeyword.MEMORY.toVar(),
                        dstOffset = memPtrSym,
                        length = numBytesSym,
                        meta = MetaMap(LONG_COPY_STRIDE to 1)
                    )
                )
            )
        }
    }


    data class CallIndirect(
        val maybeRet: Tmp?,
        val elemIndex: Arg,
        val elemTable: WasmElem,
        val args: List<Arg>,
        val typeUse: WasmTypeUse,
        override val addr: WASMAddress? = null
    ) : StraightLine(addr) {
        override fun hasHavoc(): Boolean {
            return this.args.any { it.isHavoc() } || this.elemIndex.isHavoc()
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            val havocVarsForArgs = args.mapIndexed { i, a ->
                if (a.isHavoc()) {
                    val name = listOf(HAVOC_VAR_NM, i).joinToString(UNDERSCORE)
                    ArgRegister(Tmp(a.type, allocFresh(), name, 0))
                } else {
                    a
                }
            }
            return if (elemIndex.isHavoc()) {
                havocVarsForArgs.plus(ArgRegister(Tmp(elemIndex.type, allocFresh(), HAVOC_VAR_NM, 0)))
            } else {
                havocVarsForArgs
            }
        }

        override val tempIdxs = setOfNotNull(maybeRet?.pc)

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            throw UnexpectedCallIndirect("This should never happen, CallIndirects are converted to Calls.")
        }

        override fun toString(): String {
            return if (maybeRet != null) {
                "$maybeRet = $WASMICFG_CALL_INDIRECT[$elemIndex] ${elemTable.stringOfFuncIds()} (${args.joinToString(",") { it.toString() }})"
            } else {
                "$WASMICFG_CALL_INDIRECT[$elemIndex] ${elemTable.stringOfFuncIds()} (${args.joinToString(",") { it.toString() }})"
            }
        }
    }

    data class Call(val maybeRet: Tmp?, val id: WasmName, val args: List<Arg>, override val addr: WASMAddress? = null) :
        StraightLine(addr) {
        override fun toString(): String {
            return if (maybeRet != null) {
                "$maybeRet = $WASMICFG_CALL $id (${args.joinToString(",") { it.toString() }})"
            } else {
                "$WASMICFG_CALL $id (${args.joinToString(",") { it.toString() }})"
            }
        }

        /**
         * Any calls that are left uninlined should be summarized or havoced,
         * except the pre- and post-condition ones that should for now be specified
         * in WasmEntryPoint.kt together with the target function.
         *
         */
        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            // If we have a summary, apply it
            return if (summarizer.canSummarize(id)) {
                summarizer.summarizeCall(this)
            } else {
                // Otherwise, leave an annotation in the TAC, and havoc the return value
                mergeMany(
                    listOfNotNull(
                        TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(WASM_UNRESOLVED_CALL, id))
                            .withDecls(),
                        maybeRet?.let {
                            assignHavocPrimitive(
                                TACSymbol.Var(maybeRet.toString(), Tag.Bit256),
                                maybeRet.type
                            )
                        }
                    )
                )
            }
        }

        override fun hasHavoc(): Boolean {
            return this.args.any { it.isHavoc() }
        }

        /* This also returns the non-havoced arguments for helping with reconstruction. */
        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return args.mapIndexed { i, a ->
                if (a.isHavoc()) {
                    val name = listOf(HAVOC_VAR_NM, i).joinToString(UNDERSCORE)
                    ArgRegister(Tmp(a.type, allocFresh(), name, 0))
                } else {
                    a
                }
            }
        }

        override val tempIdxs = setOfNotNull(maybeRet?.pc)
    }

    /**
     * Make a straight-line instruction for Havoc, so it's easier to get to our TAC `AssignHavocCmd` from this.
     * */
    data class Havoc(val type: WasmPrimitiveType, val lhs: Tmp) : StraightLine() {
        override fun toString(): String {
            return "$HAVOC($lhs)"
        }

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            val lhsSym = TACSymbol.Var(lhs.toString(), Tag.Bit256) // NOTE may need some type info here from wasm.
            return assignHavocPrimitive(lhsSym, type)
        }

        override fun hasHavoc(): Boolean {
            throw CannotHaveHavocException("StraightLine.Havoc should not have any `Arg` and therefore cannot have havoc.")
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return emptyList()
        }

        override val tempIdxs = setOf(lhs.pc)
    }

    /**
     * Annotate the start of a function (useful for detecting where a function starts after inlining).
     * This is used for generating call trace.
     * */
    data class InlinedFuncStartAnnotation(
        val funcId: String,
        val funcArgs: List<Arg>,
        val callIdx: Int,
        val range: Range.Range? = null
    ) : StraightLine() {
        override fun hasHavoc(): Boolean {
            return false
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return listOf()
        }

        override val tempIdxs: Set<Int>
            get() = setOf()

        @KSerializable
        @com.certora.collect.Treapable
        data class TAC(val funcName: String, val funcArgs: List<TACSymbol>, val range: Range.Range?) :
            TransformableVarEntityWithSupport<TAC>, HasKSerializable, AmbiSerializable {

            constructor(fromWasm: InlinedFuncStartAnnotation) :
                this(fromWasm.funcId, fromWasm.funcArgs.map { it.toTacSymbol() }, fromWasm.range)

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): TAC =
                copy(funcArgs = funcArgs.map { (it as? TACSymbol.Var)?.let(f) ?: it })

            override val support: Set<TACSymbol.Var> =
                funcArgs.filterIsInstanceTo(mutableSetOf())

            override fun toString(): String =
                "TAC(funcName=${funcName.replaceHTMLEscapes()}, funcArgs=$funcArgs, range=$range)"
        }

        private fun toTacAnnot() =
            TACCmd.Simple.AnnotationCmd.Annotation(WASM_INLINED_FUNC_START, TAC(this))

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            return WasmToTacInfo(listOf(TACCmd.Simple.AnnotationCmd(toTacAnnot())), setOf())
        }
    }

    /**
     * Annotate the end of a function (useful for detecting where a function ends after inlining).
     * This is used for generating call trace.
     * */
    data class InlinedFuncEndAnnotation(
        val funcId: String,
        val callIdx: Int,
    ) : StraightLine() {
        override fun hasHavoc(): Boolean {
            return false
        }

        override fun getVarsForHavoc(allocFresh: () -> Int): List<Arg> {
            return listOf()
        }

        override val tempIdxs: Set<Int>
            get() = setOf()

        @KSerializable
        @com.certora.collect.Treapable
        data class TAC(val funcName: String) : HasKSerializable, AmbiSerializable {
            constructor(fromWasm: InlinedFuncEndAnnotation) :
                this(fromWasm.funcId)

            override fun toString(): String =
                "TAC(funcName=${funcName.replaceHTMLEscapes()})"
        }

        private fun toTacAnnot() =
            TACCmd.Simple.AnnotationCmd.Annotation(WASM_INLINED_FUNC_END, TAC(this))

        context(WasmImpCfgContext)
        override fun wasmImpcfgToTacSimpleInternal(): WasmToTacInfo {
            return WasmToTacInfo(listOf(TACCmd.Simple.AnnotationCmd(toTacAnnot())), setOf())
        }
    }
}

object WasmImpInstr {
    fun transformVars(
        st: StraightLine,
        transformTmp: (Tmp) -> Tmp,
        transformLocal: (Local) -> Local,
    ): StraightLine {
        return when (st) {
            is StraightLine.SetTmp -> {
                val lhs = transformTmp(st.lhs)
                val expr = transformTmps(st.expr, transformTmp)
                st.copy(lhs = lhs, expr = expr)
            }

            is StraightLine.SetLocal -> {
                val newLocal = transformLocal(st.local)
                val newRhs = st.arg.transformTmps(transformTmp)
                st.copy(local = newLocal, arg = newRhs)
            }

            is StraightLine.SetGlobal -> {
                val newRhs = st.arg.transformTmps(transformTmp)
                st.copy(arg = newRhs)
            }

            is StraightLine.GetLocal -> {
                val newLhs = transformTmp(st.lhs)
                val newLocal = transformLocal(st.local)
                st.copy(lhs = newLhs, local = newLocal)
            }

            is StraightLine.GetGlobal -> {
                val newLhs = transformTmp(st.lhs)
                st.copy(lhs = newLhs)
            }

            is StraightLine.Store -> {
                val newAt = st.at.transformTmps(transformTmp)
                val newValue = st.value.transformTmps(transformTmp)
                st.copy(at = newAt, value = newValue)
            }

            is StraightLine.Load -> {
                val newTo = transformTmp(st.to)
                val newFrom = st.from.transformTmps(transformTmp)
                st.copy(to = newTo, from = newFrom)
            }

            is StraightLine.Copy -> {
                val newDest = st.dest.transformTmps (transformTmp)
                val newSrc = st.source.transformTmps (transformTmp)
                val newNumBytes = st.numBytes.transformTmps (transformTmp)
                st.copy(dest = newDest, source = newSrc, numBytes = newNumBytes)
            }
            is StraightLine.Fill -> {
                val newMemPtr = st.memPtr.transformTmps (transformTmp)
                val newValue = st.value.transformTmps (transformTmp)
                val newNumBytes = st.numBytes.transformTmps (transformTmp)
                st.copy(memPtr = newMemPtr, value = newValue, numBytes = newNumBytes)
            }

            is StraightLine.Call -> {
                val newArgs = st.args.map { it.transformTmps(transformTmp) }
                if (st.maybeRet != null) {
                    val newRet = transformTmp(st.maybeRet)
                    st.copy(maybeRet = newRet, args = newArgs)
                } else {
                    st.copy(maybeRet = null, args = newArgs)
                }
            }

            is StraightLine.CallIndirect -> {
                val newArgs = st.args.map { it.transformTmps(transformTmp) }
                val newElemTableIdx = st.elemIndex.transformTmps(transformTmp)
                if (st.maybeRet != null) {
                    val newRet = transformTmp(st.maybeRet)
                    st.copy(maybeRet = newRet, elemIndex = newElemTableIdx, args = newArgs)
                } else {
                    st.copy(maybeRet = null, elemIndex = newElemTableIdx, args = newArgs)
                }
            }

            is StraightLine.Havoc -> {
                val newVar = transformTmp(st.lhs)
                st.copy(lhs = newVar)
            }

            is StraightLine.InlinedFuncStartAnnotation -> {
                st.copy(funcArgs = st.funcArgs.map { it.transformTmps(transformTmp) })
            }

            is StraightLine.InlinedFuncEndAnnotation -> {
                st
            }
        }
    }

    fun transformControl(
        ctrl: Control,
        transformTmp: (Tmp) -> Tmp,
        transformBlock: (PC) -> PC,
    ): Control {
        return when (ctrl) {
            is Control.Jump -> {
                ctrl.copy(transformBlock(ctrl.pc))
            }

            is Control.Brif -> {
                val newIf = transformBlock(ctrl.ifpc)
                val newElse = transformBlock(ctrl.elpc)
                val newCond = ctrl.arg.transformTmps(transformTmp)
                ctrl.copy(arg = newCond, ifpc = newIf, elpc = newElse)
            }

            is Control.Ret -> {
                if (ctrl.arg != null) {
                    ctrl.copy(arg = ctrl.arg.transformTmps(transformTmp))
                } else {
                    ctrl
                }
            }

            is
            Control.CertoraUnreachable -> {
                ctrl
            }

            is
            Control.Unreach -> {
                ctrl
            }

            is Control.BrTable -> {
                throw AlphaRenamingControlCmdFailed("BrTable should have been removed")
            }
        }
    }

    /**
     * Used for introducing statements that use the havoced variables in the program.
     * */
    fun reconstructStraight(st: StraightLine, vars: List<Arg>): StraightLine {
        return when (st) {
            is StraightLine.SetTmp -> {
                st.copy(expr = reconstructExpr(st.expr, vars))
            }

            is StraightLine.SetLocal -> {
                assert(vars.size == 1)
                st.copy(arg = vars[0])
            }

            is StraightLine.SetGlobal -> {
                assert(vars.size == 1)
                st.copy(arg = vars[0])
            }

            is StraightLine.GetLocal -> {
                st
            }

            is StraightLine.GetGlobal -> {
                st
            }

            is StraightLine.InlinedFuncStartAnnotation -> {
                st
            }

            is StraightLine.InlinedFuncEndAnnotation -> {
                st
            }

            is StraightLine.Store -> {
                assert(vars.size == 2)
                st.copy(at = vars[0], value = vars[1])
            }

            is StraightLine.Load -> {
                assert(vars.size == 1)
                st.copy(from = vars[0])
            }

            is StraightLine.Copy -> {
                assert(vars.size == 3)
               st.copy(dest = vars[0], source = vars[1], numBytes = vars[2])
            }

            is StraightLine.Fill -> {
                assert(vars.size == 3)
                st.copy(memPtr = vars[0], value = vars[1], numBytes = vars[2])
            }

            is StraightLine.Call -> {
                st.copy(args = vars)
            }

            is StraightLine.CallIndirect -> {
                throw UnexpectedCallIndirect("All call_indirects should have been translated to calls by now.")
            }

            is StraightLine.Havoc -> {
                throw CannotHaveHavocException("This is already a havoc statement.")
            }
        }
    }

    fun createVarNameHelper(localOrGlobal: String, idx: String): String {
        return localOrGlobal + UNDERSCORE + idx
    }
}

private fun String.replaceHTMLEscapes(): String =
    replace("<", "&lt").replace(">", "&gt")

