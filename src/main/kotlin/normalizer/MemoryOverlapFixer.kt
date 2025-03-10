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

package normalizer

import analysis.*
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import evm.MASK_SIZE
import optimizer.isMemoryAccess
import tac.MetaMap
import tac.Tag
import utils.EVMOps
import utils.divRoundUp
import utils.times
import utils.toIntOrNull
import vc.data.*
import vc.data.TACMeta.EVM_MEMORY
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger

/**
 * Fixes the memory overlapping soundness issue for a subset of the cases which do not require symbolic reasoning on offsets
 * https://www.notion.so/certora/Memory-overlapping-42b2ec2cfc694d74ab2afac5f07bb56c
 */
object MemoryOverlapFixer {

    /**
     * An entry for an mstore or assignment to memory scalar that we are generating additional writes from (for future reads)
     */
    data class WriteToUpdate(val where: CmdPointer, val readLocToUpdate: BigInteger, val currentWriteLoc: BigInteger, val writeSym: TACSymbol, val base: TACSymbol.Var) {
        init {
            check(readLocToUpdate != currentWriteLoc) { "No reason to fix overlapping if read offset is the same as the write offset" }
        }

        /**
         * generates the alternative writes for ensuring future reads of [readLocToUpdate] are consistent
         */
        fun alternativeWrite(): CommandWithRequiredDecls<TACCmd.Simple> {
            // read the location of the read as it is at the moment of writing to it
            val readSym = TACKeyword.TMP(Tag.Bit256, "readPrev")
            val readCmd = properRead(readSym, readLocToUpdate, base)
            // returns a pair of the commands and their "out" variable
            val updatedWriteValueCmd = if (currentWriteLoc > readLocToUpdate) {
                updateFromLaterWrite(readLocToUpdate, readSym, currentWriteLoc, writeSym)
            } else {
                // necessarily currentWriteLoc < readLocToUpdate
                updateFromEarlierWrite(readLocToUpdate, readSym, currentWriteLoc, writeSym)
            }
            val startLabel = TACCmd.Simple.LabelCmd("→ Instrumented write to ${readLocToUpdate.toString(16)} from a write to ${currentWriteLoc.toString(16)}")
            val endLabel = TACCmd.Simple.LabelCmd("←")
            return CommandWithRequiredDecls(startLabel)
                .merge(readCmd, readSym)
                .merge(updatedWriteValueCmd.first)
                .merge(properStore(updatedWriteValueCmd.second, readLocToUpdate, base))
                .merge(endLabel)
        }

        companion object {
            // Accounts for the possibility of a store being to a scalar, and not as a mapping-store
            private fun properStore(writtenValue: TACSymbol, writeLoc: BigInteger, base: TACSymbol.Var): TACCmd.Simple {
                fun defaultStore() = TACCmd.Simple.AssigningCmd.ByteStore(
                    writeLoc.asTACSymbol(),
                    writtenValue,
                    base,
                    meta = MetaMap(EVM_MEMORY)
                )
                if (!Config.Mem0x0To0x40AsScalar.get() &&
                    (writeLoc == BigInteger.ZERO || writeLoc == BigInteger.valueOf(32))) {
                    return defaultStore()
                }

                return when (writeLoc) {
                    BigInteger.ZERO -> TACCmd.Simple.AssigningCmd.AssignExpCmd(TACKeyword.MEM0.toVar(), writtenValue)
                    BigInteger.valueOf(32) -> TACCmd.Simple.AssigningCmd.AssignExpCmd(TACKeyword.MEM32.toVar(), writtenValue)
                    else -> defaultStore()
                }
            }

            // Accounts for the possibility of a load being to a scalar, and not as a mapping-store
            private fun properRead(readValue: TACSymbol.Var, readLoc: BigInteger, base: TACSymbol.Var): TACCmd.Simple {
                fun defaultLoad() = TACCmd.Simple.AssigningCmd.ByteLoad(
                    readValue,
                    readLoc.asTACSymbol(),
                    base,
                    meta = MetaMap(EVM_MEMORY)
                )
                if (!Config.Mem0x0To0x40AsScalar.get() &&
                    (readLoc == BigInteger.ZERO || readLoc == BigInteger.valueOf(32))) {
                    return defaultLoad()
                }
                return when (readLoc) {
                    BigInteger.ZERO -> TACCmd.Simple.AssigningCmd.AssignExpCmd(readValue, TACKeyword.MEM0.toVar())
                    BigInteger.valueOf(32) -> TACCmd.Simple.AssigningCmd.AssignExpCmd(readValue, TACKeyword.MEM32.toVar())
                    else -> defaultLoad()
                }
            }

            fun updateFromLaterWrite(
                l1: BigInteger, // read loc
                v1: TACSymbol, // read value
                l2: BigInteger, // write loc
                v2: TACSymbol // write value
            ): Pair<CommandWithRequiredDecls<TACCmd.Simple>, TACSymbol.Var> {
                val shift = (l2 - l1).times(BigInteger.valueOf(8)).toInt() /* we know l2-l1 is < 32 */
                val leftMask = EVMOps.highOnes(shift)

                return ExprUnfolder.unfoldToSingleVar("memOverlap", TACExprFactUntyped {
                    Add(
                        BWAnd(v1.asSym(), leftMask.asTACExpr),
                        ShiftRightLogical(v2.asSym(), shift.asTACExpr)
                    )
                }).let {
                    CommandWithRequiredDecls(it.cmds, it.newVars) to (it.e.s as TACSymbol.Var)
                }
            }

            fun updateFromEarlierWrite(
                l1: BigInteger, // read loc
                v1: TACSymbol, // read value
                l2: BigInteger, // write loc
                v2: TACSymbol // write value
            ): Pair<CommandWithRequiredDecls<TACCmd.Simple>, TACSymbol.Var> {
                val shift = (l1 - l2).times(BigInteger.valueOf(8)).toInt() /* we know l2-l1 is < 32 */
                val rightMask = MASK_SIZE(shift)

                return ExprUnfolder.unfoldToSingleVar("memOverlap", TACExprFactUntyped {
                    Add(
                        ShiftLeft(v2.asSym(), shift.asTACExpr),
                        BWAnd(v1.asSym(), rightMask.asTACExpr)
                    )
                }).let {
                    CommandWithRequiredDecls(it.cmds, it.newVars) to (it.e.s as TACSymbol.Var)
                }
            }
        }
    }

    /**
     * [where] is the command pointer where the read occurs
     * [sym] represents a constant location being read
     */
    data class ConstantReadToProcess(val where: CmdPointer, val sym: BigInteger)

    // fetch relevant reads from the command [lcmd], could be multiple in case of hashing
    private fun relevantReads(lcmd: LTACCmd): List<ConstantReadToProcess> {
        val c = lcmd.cmd
        val ret = mutableListOf<ConstantReadToProcess>()
        val readWhere = lcmd.ptr
        when (c) {
            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                if (c.base.meta.containsKey(EVM_MEMORY) && c.loc is TACSymbol.Const) {
                    ret += ConstantReadToProcess(readWhere, c.loc.value)
                }
            }

            is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> {
                c.args.map { s ->
                    if (s is TACSymbol.Var) {
                        val keyword = s.meta[KEYWORD_ENTRY]?.name
                        if (keyword == TACKeyword.MEM0.getName()) {
                            ret += ConstantReadToProcess(readWhere, BigInteger.ZERO)
                        } else if (keyword == TACKeyword.MEM32.getName()) {
                            ret += ConstantReadToProcess(readWhere, BigInteger.valueOf(32))
                        }
                    }
                }
            }

            is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                if (c.memBaseMap.meta.containsKey(EVM_MEMORY)) {
                    (c.op1 as? TACSymbol.Const)?.value?.let { startOffsetConst ->
                        // num of words we will instrument for is bounded from above by the chosen precision length for hashed buffers
                        val numWords = Math.min(
                            (c.op2 as? TACSymbol.Const)?.value?.divRoundUp(EVM_WORD_SIZE)?.toIntOrNull() ?: 1,
                            Config.PreciseHashingLengthBound.get().divRoundUp(EVM_WORD_SIZE_INT)
                        )
                        repeat(numWords) { i ->
                            ret += ConstantReadToProcess(readWhere, startOffsetConst + EVM_WORD_SIZE.times(i))
                        }
                    }
                }
            }

            else -> {}
        }
        return ret
    }

    /**
     * [where] is the command pointer where the write (store/assignment) occurs
     * [loc] represents a constant location being written
     * [writtenValue] is the written value
     * [base] is the memory base in case of a store
     */
    data class WriteToProcess(val where: CmdPointer, val loc: BigInteger, val writtenValue: TACSymbol, val base: TACSymbol.Var)

    // if the command [lcmd] represents a relevant memory update (store/assign) to an offset [offset] we read later, returns a [WriteToProcess] entry
    private fun relevantWrite(lcmd: LTACCmd, offset: BigInteger): WriteToProcess? {
        val c = lcmd.cmd
        val writeWhere = lcmd.ptr
        return when (c) {
            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                if (c.base.meta.containsKey(EVM_MEMORY) && c.loc is TACSymbol.Const) {
                    val loc = c.loc.value
                    if ((loc - offset).abs() < EVM_WORD_SIZE) { // within 32 bytes
                        WriteToProcess(writeWhere, loc, c.value, c.base)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                // a very simplistic and easy-to-get-rid-of assumption that rhs is simple
                if (c.rhs !is TACExpr.Sym) {
                    null
                } else if (c.lhs.meta[KEYWORD_ENTRY]?.name == TACKeyword.MEM0.getName() && offset < EVM_WORD_SIZE) {
                    WriteToProcess(writeWhere, BigInteger.ZERO, c.rhs.s, TACKeyword.MEMORY.toVar())
                } else if (c.lhs.meta[KEYWORD_ENTRY]?.name == TACKeyword.MEM32.getName() && (EVM_WORD_SIZE - offset).abs() < EVM_WORD_SIZE) {
                    WriteToProcess(writeWhere, BigInteger.valueOf(32), c.rhs.s, TACKeyword.MEMORY.toVar())
                } else {
                    null
                }
            }

            else -> null
        }
    }

    // returns true if we should stop the reverse iteration from [lcmd] assuming we try to find relevant writes for [readOffset]
    private fun isBreakingAccess(lcmd: LTACCmd, readOffset: BigInteger) = (lcmd.isMemoryAccess() && when (lcmd.cmd) {
        is TACCmd.Simple.AssigningCmd.ByteStore -> lcmd.cmd.loc !is TACSymbol.Const
            || lcmd.cmd.loc.value == BigInteger.valueOf(64) /* don't want to mess with freememptr */
            || lcmd.cmd.loc.value == readOffset /* if we are writing to the same offset we read, there's nothing to fix */
        is TACCmd.Simple.AssigningCmd.ByteLoad -> false
        else -> true
    }) || /* again, don't want to mess with the freememptr */ lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>().let { assignLCmd ->
        assignLCmd != null && assignLCmd.cmd.lhs.meta[KEYWORD_ENTRY]?.name == TACKeyword.MEM64.getName()
    }

    /**
     * Performs the actual fixing of memory overlaps by making sure the constant reads are consistent with adjacent writes
     */
    fun ensureConsistentReads(c: CoreTACProgram): CoreTACProgram {
        val g = c.analysisCache.graph
        val constantReads = g.commands.flatMap { relevantReads(it) }
        val writesToUpdate = mutableMapOf<CmdPointer, MutableList<WriteToUpdate>>()
        constantReads.forEach { (where, readOffset) ->
            // we will only look at current block
            val backwardsIter = g.iterateRevBlock(where)
            for (lcmd in backwardsIter) {
                if (isBreakingAccess(lcmd, readOffset)) {
                    break
                }
                val write = relevantWrite(lcmd, readOffset)
                if (write != null) {
                    // if write loc is the same as read loc, we're done (should be checked by breakingAccess actually)
                    val writeOffset = write.loc
                    if (writeOffset == readOffset) {
                        break
                    }
                    writesToUpdate.computeIfAbsent(lcmd.ptr) { mutableListOf() }
                        .add(WriteToUpdate(lcmd.ptr, readOffset, writeOffset, write.writtenValue, write.base))
                }
            }
        }

        return c.patching { patching ->
            writesToUpdate.forEachEntry { (where, writesToUpdate) ->
                val cmdsToAdd = writesToUpdate.flatMap { writeToUpdate ->
                    writeToUpdate.alternativeWrite().also { patching.addVarDecls(it.varDecls) }.cmds
                }
                patching.addBefore(
                    where,
                    cmdsToAdd
                )
            }
        }
    }
}
