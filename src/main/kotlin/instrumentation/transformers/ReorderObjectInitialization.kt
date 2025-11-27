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

package instrumentation.transformers

import analysis.*
import analysis.pta.*
import datastructures.stdcollections.*
import log.*
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.tacexprutil.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.INITIALIZATION)

/**
    Attempts to reorder memory writes during object initialization so that they conform to the expectations of
    [SimpleInitializationAnalysis].  We look for sequences of memory writes following a read from the free pointer,
    where the memory locations written to are not monotonically increasing, and we reorder them to be so (subject to a
    few constraints).
 */
object ReorderObjectInitialization {

    val FENCE = MetaKey.Nothing("init.reorder.fence")

    /**
        Inserts fence annotations to ensure that constant-size array allocations keep the write of the (constant) length
        after any previous writes.

        We could probably just do this check in [rewriteSegment], this code predates that code, and it works.
     */
    fun reorderingFenceInstrumentation(c: CoreTACProgram) : CoreTACProgram {
        return c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf { store ->
                store.cmd.base == TACKeyword.MEMORY.toVar() &&
                    store.cmd.loc is TACSymbol.Var &&
                    store.cmd.value is TACSymbol.Const &&
                    c.analysisCache.def.defSitesOf(store.cmd.loc as TACSymbol.Var, store.ptr).singleOrNull()?.takeIf {
                        it.block == store.ptr.block
                    }?.let(c.analysisCache.graph::elab)?.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                        it.cmd.rhs == TACKeyword.MEM64.toVar().asSym()
                    }?.let {
                        c.analysisCache.graph.iterateBlock(start = it.ptr, end = store.ptr.pos, excludeStart = true).none {
                            it.cmd.getLhs() == TACKeyword.MEM64.toVar()
                        }
                    } == true
            }
        }.patchForEach(c) {
            this.addBefore(it.ptr, listOf(TACCmd.Simple.AnnotationCmd(
                FENCE
            )))
        }
    }

    /**
        Reorders object initialization writes in the given program.
     */
    fun rewrite(p: CoreTACProgram): CoreTACProgram {
        val mut = p.toPatchingProgram()
        for (b in p.analysisCache.graph.blocks) {
            rewriteBlock(p, b, mut)
        }
        return mut.toCode(p)
    }

    private fun rewriteBlock(
        p: CoreTACProgram,
        b: TACBlock,
        mut: SimplePatchingProgram
    ) {
        // Group the commands in the block into segments to be reordered.  A segment is the commands between a read from
        // the FP and a write to the FP (or the end of the block), *or* the commands between a write to the FP and the
        // next read from the FP / end of block.
        //
        // I.e., we reorder the memory writes between two FP reads, but do not allow reordering across FP writes.
        var currFp: TACSymbol.Var? = null
        var inAlloc: Boolean = false
        b.commands.groupBy {
            if (it.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                if (it.cmd.rhs == TACKeyword.MEM64.toVar().asSym()) {
                    inAlloc = true
                    currFp = it.cmd.lhs
                    return@groupBy null // Don't include this command in the init group
                } else if (it.cmd.lhs == TACKeyword.MEM64.toVar()) {
                    inAlloc = false
                    return@groupBy null // Don't include this command in the init group
                }
            }
            currFp?.let { it to inAlloc }
        }.forEachEntry { (fpAndAlloc, cmds) ->
            val (fp, _) = fpAndAlloc ?: return@forEachEntry
            rewriteSegment(p, fp, cmds, mut)
        }
    }

    private sealed class Value {
        /** A known constant value */
        data class Const(val value: BigInteger) : Value()
        /** A known offset from the previously read FP value */
        data class Offset(val offset: BigInteger) : Value()
        /** A length of memory (computed by subtracting two offsets) */
        object Length : Value()
        /** An unknown value */
        object Unknown : Value()
    }

    private fun rewriteSegment(
        p: CoreTACProgram,
        fp: TACSymbol.Var,
        cmds: List<LTACCmd>,
        mut: SimplePatchingProgram
    ) {
        // Abstract values for each variable we encounter
        val values = mutableMapOf<TACSymbol.Var, Value>(fp to Value.Offset(BigInteger.ZERO))

        // Evaluates an expression.  Returns null if e reads from memory, or appears to be nonsensical.
        fun eval(e: TACExpr): Value? {
            return when (e) {
                is TACExpr.Sym.Const -> Value.Const(e.s.value)
                is TACExpr.Sym.Var -> values[e.s] ?: Value.Unknown.also {
                    // If we see a variable we don't know about, treat it as an assignment; if we later see another
                    // assignment, we'll abort this initialization.
                    values[e.s] = it
                }
                is TACExpr.Vec.Add -> e.ls.map { eval(it) }.reduce { a, b ->
                    when (a) {
                        null -> null
                        Value.Unknown -> Value.Unknown
                        is Value.Const -> when (b) {
                            null -> null
                            Value.Unknown -> Value.Unknown
                            is Value.Const -> Value.Const(a.value + b.value)
                            is Value.Offset -> Value.Offset(a.value + b.offset)
                            is Value.Length -> Value.Length
                        }
                        is Value.Offset -> when (b) {
                            null -> null
                            Value.Unknown -> Value.Unknown
                            is Value.Const -> Value.Offset(a.offset + b.value)
                            is Value.Offset -> null
                            Value.Length -> null
                        }
                        Value.Length -> when (b) {
                            null -> null
                            Value.Unknown -> Value.Unknown
                            is Value.Const -> Value.Length
                            is Value.Offset -> null
                            Value.Length -> null
                        }
                    }
                }
                is TACExpr.BinOp.Sub -> {
                    val o1 = eval(e.o1) ?: return null
                    val o2 = eval(e.o2) ?: return null
                    when {
                        o1 is Value.Offset && o2 is Value.Offset -> Value.Length
                        else -> Value.Unknown
                    }
                }
                else -> when {
                    e.subs.any { it is TACExpr.Select || it is TACExpr.StoreExpr } -> null
                    else -> Value.Unknown
                }
            }
        }

        // Track whether the last write in the segment wrote a length value (we might want to preserve its position)
        var lastWriteIsLength = false

        // Find the offsets from the FP, for each memory write in the segment.  If we encounter anything that would
        // prevent us from reordering this segment (such as a read from memory, or an expression we can't evaluate),
        // we abort the whole segment.
        val writesWithOffsets = cmds.takeWhile {
            // Stop at fences; we can't reorder across them
            !it.maybeAnnotation(FENCE)
        }.mapNotNull {
            when (it.cmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    val value = eval(it.cmd.rhs) ?: run {
                        logger.debug { "Cannot evaluate expression in ${p.name} at $it; skipping reordering" }
                        return
                    }
                    val overwritten = values.put(it.cmd.lhs, value)
                    if (overwritten != null) {
                        logger.debug { "Variable assigned multiple times in ${p.name}: ${it.cmd.lhs}; skipping reordering" }
                        return
                    }
                    null
                }
                is TACCmd.Simple.AssigningCmd.ByteStore -> {
                    if (it.cmd.base != TACKeyword.MEMORY.toVar()) {
                        logger.debug { "Writing to non-memory location in ${p.name} at $it; skipping reordering" }
                        return
                    }
                    if (it.cmd.loc !is TACSymbol.Var) {
                        logger.debug { "Writing to constant offset in ${p.name} at $it; skipping reordering" }
                        return
                    }
                    val offset = values[it.cmd.loc] as? Value.Offset
                    if (offset == null) {
                        logger.debug { "Cannot determine offset in ${p.name} at $it; skipping reordering" }
                        return
                    }
                    val value = eval(it.cmd.value.asSym())
                    if (value == null) {
                        logger.debug { "Writing from memory or nonsensical expression in ${p.name} at $it; skipping reordering" }
                        return
                    }
                    if (value == Value.Length) {
                        lastWriteIsLength = true
                    } else {
                        lastWriteIsLength = false
                    }
                    it to offset.offset
                }
                is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                    logger.debug { "Reading from memory in ${p.name} at $it; skipping reordering" }
                    return
                }
                else -> {
                    // Ignore other commands.
                    null
                }
            }
        }

        if (writesWithOffsets.size < 2) {
            // Nothing to reorder
            return
        }

        // If the last write is a length, and the other writes are already in order, this is probably a dynamic array
        // initialization; don't reorder it.
        if (lastWriteIsLength) {
            val inOrder =
                writesWithOffsets.size <= 2 ||
                writesWithOffsets.dropLast(1).zipWithNext().all { (a, b) -> a.second <= b.second }
            if (inOrder) {
                val (c, _) = writesWithOffsets.last()
                logger.debug { "Dynamic array initialization in ${p.name} at $c; skipping reordering" }
                return
            }
        }

        // Sort the writes by offset (and make sure there are no duplicate offsets)
        val writesByOffset = buildSortedMap {
            writesWithOffsets.forEach { (c, offset) ->
                if (put(offset, c.cmd) != null) {
                    // Two writes to the same offset; skip this whole initialization
                    logger.debug { "Multiple writes to same offset in ${p.name} at $c; skipping reordering" }
                    return
                }
            }
        }

        // Move the sorted writes to the position of the last original write
        writesWithOffsets.forEachIndexed { i, (c, _) ->
            if (i != writesWithOffsets.lastIndex) {
                mut.delete(c.ptr)
            } else {
                mut.replaceCommand(c.ptr, writesByOffset.values.toList())
            }
        }
    }
}
