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

package wasm.host.near

import algorithms.UnionFind
import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.Direction
import analysis.JoinLattice
import analysis.LTACCmd
import analysis.TACBlock
import analysis.TACCommandDataflowAnalysis
import analysis.TACCommandGraph
import analysis.numeric.IntValue
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import analysis.pta.abi.UnindexedPartition
import analysis.split.Ternary.Companion.isPowOf2
import com.certora.collect.TreapMap
import com.certora.collect.plus
import com.certora.collect.treapMapOf
import log.Logger
import log.LoggerTypes
import optimizer.UNINDEXED_PARTITION
import tac.Tag
import tac.generation.assert
import tac.generation.assign
import tac.generation.letVar
import tac.generation.memStore
import utils.Either
import utils.associateNotNull
import utils.`impossible!`
import utils.mapNotNull
import utils.mapNotNullToSet
import utils.mapToSet
import utils.safeAsInt
import utils.toLeft
import utils.toRight
import utils.tryAs
import utils.uncheckedAs
import utils.unused
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACExprFactoryExtensions.add
import vc.data.TACExprFactoryExtensions.div
import vc.data.TACExprFactoryExtensions.mul
import vc.data.TACKeyword
import vc.data.TACSymbol
import vc.data.TXF
import vc.data.ToTACExpr
import vc.data.asTACExpr
import vc.data.asTACSymbol
import vc.data.tacexprutil.asConst
import wasm.analysis.WASM_STACK_DEC
import wasm.analysis.WASM_STACK_INC
import wasm.analysis.intervals.IntervalAnalysis
//import wasm.analysis.intervals.IntervalAnalysis
import wasm.impCfg.WASM_MEMORY_OP_WIDTH
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.ALIAS_ANALYSIS)

private val stackTop = BigInteger.TWO.pow(20)

class Abort: Exception("PointerAnalysis Failure")

/** A stack decrement at [where] of size ([range.ub] - [range.lb]) */
private data class StackFrame(val where: CmdPointer, val range: IntValue)

private sealed class PointerValue {
    // Some decisions to revisit
    //  - May need a separate set of constants abstraction - maybe Junk can add Set<Int>?
    //  - Don't really need to track stack frames, is it really a useful sanity check
    //    (that a pointer value doesn't cross frame boundaries)
    fun id(): UnindexedPartition = ids.getOrPut(this) { UnindexedPartition.new() }

    companion object {
        private val ids = ConcurrentHashMap<PointerValue, UnindexedPartition>()
    }

    object Junk: PointerValue()

    sealed interface Heap
    /** Definitely a heap pointer (i.e., definitely greater than [stackTop]) */
    object HeapPointer : PointerValue(), Heap
    /** Possibly a heap pointer (i.e., possibly greater than [stackTop]) */
    object MaybeHeapPointer : PointerValue(), Heap

    sealed class Stack: PointerValue() {
        /** This pointer points into one of [frames] */
        abstract val frames: Set<StackFrame>

        /** If not null, the pointer is equal to a value in [bases] */
        abstract val bases: Set<BigInteger>?

        abstract fun addConst(c: BigInteger): PointerValue

        fun killBases(): Stack =
            when(this) {
                is MaybeStackPointer -> copy(bases = null)
                is StackPointer -> copy(bases = null)
            }

        /**
         * A value that may be a pointer into [frames] with possible values in [bases].
         *
         * This abstraction is essentially a hint: it denotes any value. If dereferenced, the prover must
         * _check_ that it can take on a value described by [frames]/[bases] (i.e., by inserting an assertion)
         */
        data class MaybeStackPointer(override val frames: Set<StackFrame>, override val bases: Set<BigInteger>?) : Stack() {
            init {
                bases?.forEach { b ->
                    check(frames.any { f -> f.range.mayEqual(b) }) {
                        "Invariant violation: invalid base $b not contained in $frames"
                    }
                }
            }

            override fun addConst(c: BigInteger): PointerValue {
                if (bases == null) {
                    return this
                }

                val newBases = bases
                    .mapNotNullToSet { (it + c).takeIf { frames.any { f -> f.range.mayEqual(it) } } }

                if (newBases.isEmpty()) {
                    return Junk
                }

                return copy(bases = newBases)
            }
        }

        /**
         * A value that is known to point within one of the ranges delimited by [frames] and, if not null,
         * is equal to some value in [bases].
         */
        data class StackPointer(override val frames: Set<StackFrame>, override val bases: Set<BigInteger>?) : Stack() {
            init {
                bases?.forEach { b ->
                    check(frames.any { f -> f.range.mayEqual(b) }) {
                        "Invariant violation: invalid base $b not contained in $frames"
                    }
                }
            }

            override fun addConst(c: BigInteger): PointerValue {
                if (bases == null) {
                    return MaybeStackPointer(frames = frames, bases = null)
                }
                val newBases = bases.mapNotNullToSet { (it + c).takeIf { frames.any { f -> f.range.mayEqual(it) } } }

                if (newBases.isEmpty()) {
                    return Junk
                }

                if (newBases.size != bases.size) {
                    // Maybe we overflowed a frame, so check this pointer if we use it
                    return MaybeStackPointer(frames, newBases)
                }

                return this.copy(bases = newBases)
            }
        }
    }

    fun toMaybe(): PointerValue =
        when (this) {
            is HeapPointer -> MaybeHeapPointer
            is Stack.StackPointer -> Stack.MaybeStackPointer(this.frames, this.bases)
            else -> this
        }

    fun join(o: PointerValue): PointerValue? {
        return when {
            this == o -> this
            this is Stack.StackPointer && o is Stack.StackPointer -> {
                val bs = bases?.let { b1 -> o.bases?.let { b2 -> b1.union(b2) }}
                Stack.StackPointer(frames + o.frames, bs)
            }
            this is Stack && o is Stack -> {
                val bs = bases?.let { b1 -> o.bases?.let { b2 -> b1.union(b2) }}
                Stack.MaybeStackPointer(frames + o.frames, bs)
            }
            this is Junk -> o.toMaybe()
            o is Junk -> this.toMaybe()
            this is Heap && o is Heap -> MaybeHeapPointer
            else -> null
        }
    }
}

/**
 * @param var variable -> pointer value abstraction
 * @param mem mem[a] |-> (v?, iv) reading a 32 bit value at `a` produces a value
 *            with [PointerValue] abstraction v (if not null)
 *            and numeric abstraction iv
 * @param frames the current stack (as pushed frames), frames[0] is the "top"
 * @param bottom true <=> unreachable state
 */
private data class StackPointerState(
    val vars: TreapMap<TACSymbol.Var, PointerValue>,
    val mem: TreapMap<BigInteger, Pair<PointerValue?, SimpleQualifiedInt>>,
    val frames: List<StackFrame>?,
    val bottom: Boolean,
) {

    init {
        check(!bottom || (vars.isEmpty() && frames == null) )
    }

    fun join(s: StackPointerState): StackPointerState {
        if (bottom) {
            return s
        }
        if (s.bottom) {
            return this
        }
        // Pointwise join of scalar vars
        val vs = vars.parallelMerge(s.vars) { _, v1, v2 ->
            (v1 ?: PointerValue.Junk).join(v2 ?: PointerValue.Junk)
        }

        // Require the stack looks identical in both branches
        val fs = frames.takeIf { it == s.frames }

        // Pointwise join of each memory location
        val m = mem.parallelMerge(s.mem) { _, v1, v2 ->
            // Lift null pointer abstractions to just Junk
            // (e.g. so that if we join null and Stack we get MaybeStack)
            val p = (v1?.first ?: PointerValue.Junk).join(v2?.first ?: PointerValue.Junk)
            val i = if (v1 == null || v2 == null) {
                SimpleQualifiedInt.nondet
            } else {
                v1.second.join(v2.second)
            }
            p to i
        }

        return StackPointerState(vars = vs, frames = fs, mem = m, bottom = false)
    }

    /** Find the stack frame that contains [r]
    */
    fun findFrameForRange(r: IntValue): StackFrame? =
        frames?.singleOrNull {
            r.mayOverlap(it.range.withUpperBound(it.range.ub - BigInteger.ONE))
        }

    fun interpret(s: TACSymbol): PointerValue? =
        when (s) {
            is TACSymbol.Const -> if (s.value < stackTop) {
                findFrameForRange(IntValue.Constant(s.value))?.let {
                    PointerValue.Stack.StackPointer(setOf(it), bases = setOf(s.value))
                }
            } else {
                PointerValue.HeapPointer
            }

            is TACSymbol.Var -> vars[s]
        }

    fun kill(modifiedVar: TACSymbol.Var?): StackPointerState =
        if (modifiedVar == null) {
            this
        } else {
            copy(vars.remove(modifiedVar))
        }

    /**
     * Model the effect of copying [len] bytes from the location
     * abstracted by [srcPointer], [srcRange] to
     * to the location abstracted by [dstPointer], [dstRange]
     */
    fun memcpy(
        dstPointer: PointerValue?,
        dstRange: IntValue,
        srcPointer: PointerValue?,
        srcRange: IntValue,
        len: BigInteger,
    ): StackPointerState {
        // We only model Stack "precisely"
        if (dstPointer !is PointerValue.Stack) {
            return this
        }
        // A set of contiguous ranges denoting the destination address
        val dstStarts = if (dstRange.isConstant) {
            setOf(dstRange)
        } else if (dstPointer.bases != null) {
            dstPointer.bases!!.mapToSet { IntValue.Constant(it) }
        } else {
            dstPointer.frames.mapToSet { f -> f.range.withLowerBound(dstRange.lb).withUpperBound(dstRange.ub - len + BigInteger.ONE)}
        }

        // Now kill any addresses thaty might be overwritten
        val dstWriteIntervals = dstStarts.mapToSet { IntValue(it.lb, it.ub + len - BigInteger.ONE)}
        val killDst = mem
            .parallelUpdateValues { x, v ->
                v.takeUnless {
                    dstWriteIntervals.any { it.mayEqual(x) }
                }
            }

        // This can almost certainly be improved
        return if (!srcRange.isConstant || !dstRange.isConstant || srcPointer !is PointerValue.Stack) {
            copy(mem = killDst)
        } else {
            // strongest of updates
            val match = killDst.entries.associateNotNull { (p, v) ->
                Pair(p - srcRange.c + dstRange.c, v).takeIf {
                    srcRange.c <= p && p < srcRange.c + len
                }
            }
            copy(mem = killDst.parallelMerge(match) { _, v1, v2 ->
                    check(v1 == null || v2 == null) {
                        "drat!"
                    }
                    v1 ?: v2
            })
        }

    }

    /**
     * Model a write to an address abstracted by ([dstPointer], [dstRange])
     * of value abstracted by ([v], [i]) with size [width] in bytes
     */
    fun store(dstPointer: PointerValue?, dstRange: IntValue, v: PointerValue?, i: SimpleQualifiedInt, width: BigInteger): StackPointerState {
        // A set of contiguous ranges denoting the destination address
        val dstAddrs = if (dstRange.isConstant) {
            setOf(dstRange)
        } else if (dstPointer is PointerValue.Stack && dstPointer.bases != null) {
            dstPointer.bases!!.map { IntValue.Constant(it) }
        } else if (dstPointer is PointerValue.Stack) {
            dstPointer
                .frames
                .mapToSet { f ->
                    f.range
                        .withLowerBound(dstRange.lb)
                        .withUpperBound(dstRange.ub - width + BigInteger.ONE)
                }
        } else if (dstPointer is PointerValue.Heap) {
            setOf(dstRange.withLowerBound(stackTop))
        } else {
            setOf(dstRange)
        }
        val writes = dstAddrs.map { it.copy(ub = it.ub + width - BigInteger.ONE)}

        // Kill any addresses this write may overlap
        val killOverlap = mem
            .parallelUpdateValues { addr, av ->
                if (writes.any { it.mayIntersect(IntValue(addr, addr + width - BigInteger.ONE)) }) {
                    null
                } else {
                    av
                }
            }
        if (dstAddrs.singleOrNull()?.isConstant == true && width == 4.toBigInteger() /* only storing pointers */) {
            val updAddr = dstAddrs.single()
            val m = killOverlap.plus(updAddr.c to (v to i))
            return this.copy(mem = m)
        }
        return this.copy(mem = killOverlap)
    }

    fun load(a: IntValue): Pair<PointerValue?, SimpleQualifiedInt> {
        return if (a.isConstant) {
            mem[a.c] ?: Pair(null, SimpleQualifiedInt.nondet)
        } else {
            Pair(null, SimpleQualifiedInt.nondet)
        }
    }

    fun set(x: TACSymbol.Var, v: PointerValue?): StackPointerState {
        if (v == null) {
            return kill(x)
        }
        return StackPointerState(vars = vars.plus(x to v), frames = frames, mem = mem, bottom = bottom)
    }

    /** Push a new frame of size [size] */
    fun push(ptr: CmdPointer, size: BigInteger): StackPointerState = copy(
        frames = frames?.let {
            (frames.firstOrNull()?.range?.lb ?: stackTop).let { top ->
                listOf(StackFrame(ptr, IntValue(top - size, top))) + it
            }
        }
    )

    /** Pop a frame of size [size] */
    fun pop(size: BigInteger): StackPointerState {
        if (frames == null) {
            return this
        }
        val r = frames.firstOrNull()?.range
        if (r == null || r.ub - r.lb != size) {
            return copy(frames = null)
        }
        return copy(frames = frames.drop(1))
    }

    companion object  {
        val initial = StackPointerState(treapMapOf(), mem = treapMapOf(), listOf(), false)
        val bot = StackPointerState(treapMapOf(), mem = treapMapOf(), null, true)
    }
}

private interface INumericAnalysis {
    fun inState(ptr: CmdPointer): SimpleQualifiedIntState?
}

private class StackPointerAnalysis(graph: TACCommandGraph) : TACCommandDataflowAnalysis<StackPointerState>(
    graph,JoinLattice.ofJoin { x, y -> x.join(y) }, StackPointerState.initial, Direction.FORWARD
) {
    /** Get the pointer abstraction of [ptr] in the pre state of [where].
     *  @return Left if the command is not reachable
     *          Right(v?) if the command _is_ reachable. v may be null if has a trivial abstraction
     */
    fun interpret(where: CmdPointer, ptr: TACSymbol): Either<Unit, PointerValue?> {
        return cmdIn[where]?.let { s ->
            ptr.asSym().toPointer(where, s).takeIf {
                !s.bottom
            }
        } ?: Unit.toLeft()
    }

    private val numeric: INumericAnalysis = object : INumericAnalysis {
        private val intervalAnalysis = graph.cache[IntervalAnalysis]
        override fun inState(ptr: CmdPointer): SimpleQualifiedIntState? =
            // This should probably just be made the default for the interval analysis
            intervalAnalysis.cachingInState(ptr)
    }

    /**
     * Consult the stack pointer state to see how to abstract the pointer
     */
    private fun StackPointerState.toPointer(p: SimpleQualifiedInt): PointerValue? =
        when {
            p.x.ub < stackTop -> findFrameForRange(p.x)?.let { frame ->
                val bases = if (p.x.isConstant) {
                    setOf(p.x.c)
                } else {
                    null
                }
                // If the pointer is definitely contained in this frame per the
                // numeric analysis, then we do not need to check when the
                // pointer is dereferenced
                if (frame.range.lb <= p.x.lb && p.x.ub < frame.range.ub) {
                    PointerValue.Stack.StackPointer(setOf(frame), bases)
                } else {
                    PointerValue.Stack.MaybeStackPointer(setOf(frame), bases)
                }
            } ?: PointerValue.Junk

            stackTop <= p.x.lb ->
                PointerValue.HeapPointer

            else ->
                null
        }

    private fun meet(where: CmdPointer, s1: PointerValue?, s2: PointerValue?): PointerValue? =
        when {
            s1 == s2 -> s1

            s1 is PointerValue.Junk && s2 != null -> s2
            s2 is PointerValue.Junk && s1 != null -> s1

            s1 is PointerValue.Stack && s2 is PointerValue.Stack -> {
                val fs = s1.frames.intersect(s2.frames)
                check (fs.isNotEmpty()) { // maybe it's ok if they're empty
                    "Empty meet @ ${graph.elab(where)}: $s1 $s2"
                }
                val bases = s1.bases?.let { b1 ->
                    s2.bases?.let { b2 -> b1.intersect(b2) } ?: b1
                } ?: s2.bases
                if (s1 is PointerValue.Stack.MaybeStackPointer && s2 is PointerValue.Stack.MaybeStackPointer) {
                    PointerValue.Stack.MaybeStackPointer(fs, bases)
                } else {
                    PointerValue.Stack.StackPointer(fs, bases)
                }
            }

            // one must be maybe, one must be heap due to s1 == s2 check
            s1 is PointerValue.Heap && s2 is PointerValue.Heap -> {
                PointerValue.HeapPointer
            }

            s1 == null -> s2
            s2 == null -> s1

            else -> `impossible!`
        }

    private fun TACExpr.toPointer(where: CmdPointer, s: StackPointerState): Either<Unit, PointerValue?> {
        val n = numeric.inState(where)?.interpret(this)
        val x = tryAs<TACExpr.Sym>()?.let { s.interpret(it.s) }
        if (n == null) {
            return Unit.toLeft()
        }
        val p = s.toPointer(n)
        return meet(where,x, p).toRight()
    }

    override fun transform(inState: StackPointerState, block: TACBlock): StackPointerState {
        // don't bother analyzing paths that must revert
        if (graph.cache.revertBlocks.contains(block.id)) {
            return StackPointerState.bot
        }
        return super.transform(inState, block)
    }

    override fun transformCmd(
        inState: StackPointerState,
        cmd: LTACCmd
    ): StackPointerState {
        if (inState.bottom) {
            return inState
        }
        when (val c = cmd.cmd) {
            is TACCmd.Simple.AssertCmd -> {
                if (c.o == false.asTACSymbol()) {
                    // Assert false halts execution, so the resulting state is bot
                    return StackPointerState.bot
                }
                return inState
            }
            is TACCmd.Simple.AssumeCmd -> {
                if (c.cond == false.asTACSymbol()) {
                    return StackPointerState.bot
                }
                return inState
            }

            is TACCmd.Simple.AnnotationCmd -> {
                return c.maybeAnnotation(WASM_STACK_DEC)?.let {
                    inState.push(cmd.ptr, it.negate())
                } ?: c.maybeAnnotation(WASM_STACK_INC)?.let {
                    inState.pop(it)
                } ?: inState
            }

            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                when {
                    // x := p % 2**32 => if p _WAS_ a pointer, then it still is (wasm32-unknown-unknown 32 bit pointers)
                    c.rhs is TACExpr.BinOp.Mod
                            && c.rhs.o2 is TACExpr.Sym.Const
                            && c.rhs.o2.s.value.isPowOf2
                            && BigInteger.TWO.pow(32) <= c.rhs.o2.s.value  -> {
                        val p = c.rhs.o1.toPointer(cmd.ptr, inState)
                        if (p is Either.Right && p.d != null) {
                            return inState.set(c.lhs, p.d!!.toMaybe())
                        }
                    }

                    c.rhs is TACExpr.TernaryExp.Ite -> {
                        val p1 = c.rhs.t.toPointer(cmd.ptr, inState).rightOrNull()
                        val p2 = c.rhs.e.toPointer(cmd.ptr, inState).rightOrNull()
                        if (p1 != null && p2 != null) {
                            return inState.set(c.lhs, p1.join(p2))
                        } else {
                            return inState.kill(c.lhs)
                        }
                    }

                    c.rhs is TACExpr.Vec.Add -> {
                        val p1 = c.rhs.o1.toPointer(cmd.ptr, inState).rightOrNull()
                        val p2 = c.rhs.o2.toPointer(cmd.ptr, inState).rightOrNull()
                        val ret = when {
                            p1 is PointerValue.MaybeHeapPointer
                                || p2 is PointerValue.MaybeHeapPointer
                                || p1 is PointerValue.HeapPointer
                                || p2 is PointerValue.HeapPointer -> PointerValue.MaybeHeapPointer

                            p1 is PointerValue.Stack && c.rhs.o2 is TACExpr.Sym.Const ->
                                p1.addConst((c.rhs.o2 as TACExpr.Sym.Const).s.value)

                            p2 is PointerValue.Stack && c.rhs.o1 is TACExpr.Sym.Const ->
                                p2.addConst((c.rhs.o1 as TACExpr.Sym.Const).s.value)

                            // Can refine this by checking the numerical invariants for o2
                            p1 is PointerValue.Stack && (p2 is PointerValue.Junk || p2 == null) -> p1.killBases().toMaybe()

                            p2 is PointerValue.Stack -> p2.killBases().toMaybe()

                            else -> null
                        }
                        return inState.set(c.lhs, ret)
                    }

                    c.rhs is TACExpr.Sym.Var -> {
                        return inState.set(c.lhs, inState.vars[c.rhs.s])
                    }

                    c.rhs is TACExpr.Sym.Const -> {
                        return inState.set(c.lhs, inState.interpret(c.rhs.s))
                    }
                }
                return inState.kill(c.lhs)
            }

            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                val p = c.loc.asSym().toPointer(cmd.ptr, inState)
                val x = numeric.inState(cmd.ptr)?.interpret(c.loc) ?: return StackPointerState.bot
                when (p) {
                    is Either.Left -> logger.warn { "Unreachable at $cmd" }
                    is Either.Right -> if (p.d == null) {
                        logger.error { "Stack analysis failure: $cmd\n$inState" }
                        throw Abort()
                    } else if (p.d is PointerValue.HeapPointer || p.d is PointerValue.MaybeHeapPointer) {
                        return inState.set(c.lhs, PointerValue.MaybeHeapPointer)
                    }
                }
                return inState.set(c.lhs, inState.load(x.x).first)
            }

            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                val p = c.loc.asSym().toPointer(cmd.ptr, inState)
                val x = numeric.inState(cmd.ptr)?.interpret(c.loc) ?: return StackPointerState.bot
                val w = c.meta[WASM_MEMORY_OP_WIDTH]!!
                when (p) {
                    is Either.Left -> logger.warn { "Unreachable at $cmd" }
                    is Either.Right -> if (p.d == null) {
                        logger.error { "Stack analysis failure: $cmd " }
                        throw Abort()
                    }
                }
                val v = c.value.asSym().toPointer(cmd.ptr, inState).rightOrNull()
                val vx = numeric.inState(cmd.ptr)?.interpret(c.value) ?: return StackPointerState.bot

                return inState.store(p.rightOrNull(),x.x, v, vx, w.toBigInteger())
            }

            is TACCmd.Simple.ByteLongCopy -> {
                val from = c.srcOffset.asSym().toPointer(cmd.ptr, inState)
                val to = c.dstOffset.asSym().toPointer(cmd.ptr, inState)
                val fromx = numeric.inState(cmd.ptr)?.interpret(c.srcOffset) ?: return StackPointerState.bot
                val tox = numeric.inState(cmd.ptr)?.interpret(c.dstOffset) ?: return StackPointerState.bot
                if (c.srcBase == TACKeyword.MEMORY.toVar() && from is Either.Right && from.d == null) {
                    logger.error { "Stack analysis failure (from): $cmd " }
                    throw Abort()
                }
                if (c.dstBase == TACKeyword.MEMORY.toVar() && to is Either.Right && to.d == null) {
                    logger.error { "Stack analysis failure (to): $cmd " }
                    throw Abort()
                }
                return inState.memcpy(srcPointer = from.rightOrNull(), srcRange = fromx.x, dstPointer = to.rightOrNull(), dstRange = tox.x, len = (c.length as TACSymbol.Const).value)
            }

            is TACCmd.Simple.AssigningCmd -> {
                return inState.kill(c.lhs)
            }

            else -> return inState
        }
    }

    init {
        logger.trace { "Running stack pointer analysis"}
        runAnalysis()
        logger.trace { "Finished stack pointer analysis"}
    }

}

private fun PointerValue.toVar(start: TACSymbol.Var): TACSymbol.Var {
    if (start != TACKeyword.MEMORY.toVar() || this is PointerValue.Heap) {
        return start
    }
    check(this is PointerValue.Stack)
    return TACSymbol.Var(
        TACKeyword.MEMORY.extendName("!stack!${id().id}"),
        Tag.ByteMap
    ).withMeta(UNINDEXED_PARTITION, id())
}


private infix fun ToTACExpr.addNonTriv(e: BigInteger): TACExpr =
    if (e == BigInteger.ZERO) { toTACExpr() } else { add(e) }

private infix fun ToTACExpr.mulNonTriv(e: BigInteger): TACExpr =
    if (e == BigInteger.ONE) { toTACExpr() } else { mul(e) }

private infix fun ToTACExpr.divNonTriv(e: BigInteger): TACExpr =
    if (e == BigInteger.ONE) { toTACExpr() } else { div(e) }

/**
 * Generate the commands to read [width] bytes from the location abstracted by [absLoc]
 * assuming memory has been divided into chunks of size [unit].
 *
 * The source pointer is [loc] + [off], and [absLoc] is the representative abstract pointer value
 */
private fun compileMemLoad(
    dst: TACSymbol.Var,
    loc: TACSymbol,
    off: BigInteger,
    absLoc: PointerValue.Stack,
    unit: Int,
    width: Int
): CommandWithRequiredDecls<TACCmd.Simple> {
    // This read will be realized by [numReads] reads
    val numReads = width / unit

    val tmps = (0 ..< numReads).map { i ->
        TACKeyword.TMP(Tag.Bit256, "read!$i")
    }

    val reads = mergeMany(
        tmps.mapIndexed { i, t ->
            TXF {
                loc.asSym() addNonTriv (off + (i * unit).toBigInteger())
            }.letVar { l ->
                val load = TACCmd.Simple.AssigningCmd.ByteLoad(
                    t,
                    loc = l.s,
                    base = absLoc.toVar(TACKeyword.MEMORY.toVar())
                )
                CommandWithRequiredDecls(load, absLoc.toVar(TACKeyword.MEMORY.toVar()))
            }
        }
    ).merge(tmps)

    val rebuild = assign(dst) {
        tmps.foldIndexed(0.asTACExpr) { i, e: TACExpr, t ->
            val tmask = t mod BigInteger.TWO.pow(8*unit).asTACExpr
            e add (tmask mulNonTriv BigInteger.TWO.pow(i * unit * 8))
        }
    }

    return mergeMany(reads, rebuild)
}

/**
 * Generate the commands to write [width] bytes to the location abstracted by [absLoc]
 * assuming memory has been divided into chunks of size [unit].
 *
 * The destination pointer is [loc] + [off], and [absLoc] is the representative abstract pointer value
 */
private fun compileMemStore(
    loc: TACSymbol,
    off: BigInteger,
    v: TACSymbol,
    absLoc: PointerValue.Stack,
    unit: Int,
    width: Int
): CommandWithRequiredDecls<TACCmd.Simple> {
    val numWrites = width / unit

    val writes = (0..<numWrites).map { i ->
        TXF { loc addNonTriv (off + (i * unit).toBigInteger()) }.letVar { l ->
            TXF { (v divNonTriv BigInteger.TWO.pow(8 * unit * i)) mod BigInteger.TWO.pow(8 * unit) }.letVar { piece ->
                val base = absLoc.toVar(TACKeyword.MEMORY.toVar())
                CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = l.s,
                        value = piece.s,
                        base = base,
                    ),
                    base
                )
            }
        }
    }

    return mergeMany(writes)
}

/**
 * A single access, which is just an (abstract) pointer plus the size (shape) of the access
 * in bytes
 */
private data class Access<S: PointerValue>(val base: S, val shape: Int) {
    val range: Set<IntValue> =
        when (base) {
            is PointerValue.Stack ->
                base.bases?.mapToSet {
                    IntValue(it, it + (shape-1).toBigInteger())
                } ?: base.frames.mapToSet { it.range.withUpperBound(newUb = it.range.ub - shape.toBigInteger()) }

            else ->
                setOf(IntValue.Nondet)
        }

    fun mayOverlap(a: Access<*>): Boolean {
        return range.any { r1 ->
            a.range.any { r2 ->
                r1.mayOverlap(r2)
            }
        }
    }
}

object NEARStackOptimizer {
    fun transform(ctp: CoreTACProgram): CoreTACProgram {
        val g = ctp.analysisCache.graph

        val stackPointers = try {
            StackPointerAnalysis(g)
        } catch (_: Abort) {
            logger.warn { "stack pointer analysis failed"}
            return ctp
        }

        val byFrame = mutableMapOf<StackFrame, MutableSet<Pair<CmdPointer, Access<PointerValue.Stack>>>>()

        val accesses = ctp
            .parallelLtacStream()
            .mapNotNull { (where, cmd) ->
                // For each memory access, build a record of the access (pointer abstraction + size)
                when (cmd) {
                    is TACCmd.Simple.DirectMemoryAccessCmd -> {
                        val ptr = stackPointers.interpret(where, cmd.loc)
                        if (ptr is Either.Right) {
                            val size = (cmd as TACCmd.Simple).meta[WASM_MEMORY_OP_WIDTH]!!
                            where to setOf(Access(ptr.d!!, size))
                        } else {
                            null
                        }
                    }

                    is TACCmd.Simple.ByteLongCopy -> {
                        val srcPtr = stackPointers.interpret(where, cmd.srcOffset)
                        val dstPtr = stackPointers.interpret(where, cmd.dstOffset)
                        if (srcPtr is Either.Left || dstPtr is Either.Left) {
                            return@mapNotNull null
                        }
                        val accesses = mutableSetOf<Access<*>>()
                        check(srcPtr is Either.Right)
                        check(dstPtr is Either.Right)

                        if (cmd.length !is TACSymbol.Const) {
                            return@mapNotNull null
                        }

                        // For memcopy, we try and break the whole thing up into 8 byte chunks, but only if
                        // the size is divisible by 8 etc, and generate an access for _each_ chunk.
                        val unit = cmd.length.asConst.gcd(8.toBigInteger())
                        val srcPtrV = srcPtr.d as? PointerValue.Stack
                        val dstPtrV = dstPtr.d as? PointerValue.Stack

                        if (srcPtrV != null) {
                            val bases = srcPtrV.bases
                            check (bases != null) {
                                "Hmph (src): $where - $cmd $srcPtrV"
                            }
                            for (chunk in (0 ..< (cmd.length.asConst/unit).safeAsInt())) {
                                val p = srcPtrV.addConst(chunk.toBigInteger() * unit)
                                accesses.add(Access(p, unit.safeAsInt()))
                            }
                        }
                        if (dstPtrV != null) {
                            check(dstPtrV.bases != null) {
                                "Hmph (dst): $where - $cmd $dstPtrV"
                            }
                            for (chunk in (0 ..< (cmd.length.asConst/unit).safeAsInt())) {
                                val p = dstPtrV.addConst(chunk.toBigInteger() * unit)
                                accesses.add(Access(p, unit.safeAsInt()))
                            }
                        }
                        where to accesses
                    }

                    else -> null
                }
            }.collect(Collectors.toSet())

        // Next, group each access by the stack frame that it touches
        for ((where, aset) in accesses) {
            for (a in aset) {
                val b = a.base
                if (b !is PointerValue.Stack) {
                    continue
                }
                b.frames.forEach {
                    byFrame
                        .getOrPut(it) { mutableSetOf() }
                        .add(where to a.uncheckedAs())
                }
            }
        }

        // uf is going to group accesses a1 and a2 into the same eq class if
        // they may overlap. We use [byFrame] to avoid checking every access,
        // since each frame has a bound
        val uf = UnionFind<Access<PointerValue.Stack>>()

        byFrame.mapValues { (_, accesses) ->
            val singleAccesses = accesses.mapNotNull {
                it.second.tryAs<Access<*>>()?.takeIf {
                    it.base is PointerValue.Stack
                }
            }
            singleAccesses.forEach { uf.register(it.uncheckedAs()) }
            var worklist = singleAccesses
            while (worklist.isNotEmpty()) {
                val h = worklist.first()
                val rest = worklist.drop(1)
                rest.forEach {
                    if (h.mayOverlap(it)) {
                        uf.union(h.uncheckedAs(), it.uncheckedAs())
                    }
                }
                worklist = rest
            }
        }

        // Each region of memory is broken up into chunks of some size,
        // which we call the 'shape'. The shape is the minimum access unit
        // across all accesses in the same eq class
        fun Access<PointerValue.Stack>.shape(): Int? {
            return uf.getEquivalenceClass(this)
                .mapNotNull { it.tryAs<Access<PointerValue.Stack>>()?.shape }
                .minOrNull()
        }

        // Get the representative access and shape for a given access at a given program point
        fun pointerAndShape(where: CmdPointer, loc: TACSymbol, width: Int, off: BigInteger = BigInteger.ZERO): Pair<Access<PointerValue.Stack>, Int>? {
            val absLoc = stackPointers.interpret(where, loc)
                .rightOrNull()
                ?.tryAs<PointerValue.Stack>()
                ?.addConst(off)
                ?.tryAs<PointerValue.Stack>()
                ?.let { it -> uf.find(Access(it, width)) }
                ?: return null
            val shape = absLoc.shape()!!
            return absLoc to shape
        }

        return ctp.patching { p ->
            // Injects the side conditions required whenever we try to dereference a Maybe* pointer.
            // We made optimistic guesses in the analysis, and now we need to verify the guesses were correct.
            fun PointerValue?.addSideCondition(loc: TACSymbol): CommandWithRequiredDecls<TACCmd.Simple> {
                unused(loc)
                if (this is PointerValue.Stack.MaybeStackPointer) {
                    return assert("stack pointer side condition") {
                        bases?.fold(false.asTACExpr) { e: TACExpr, b ->
                            e or (loc.asSym() eq b.asTACExpr)
                        } ?: run {
                            frames.fold(false.asTACExpr) { e: TACExpr, f ->
                                e or ((f.range.lb.asTACExpr le loc.asSym()) and (loc.asSym() lt f.range.ub.asTACExpr))
                            }
                        }
                    }
                } else if (this is PointerValue.MaybeHeapPointer) {
                    return assert("heap pointer side condition") {
                        stackTop.asTACExpr lt loc.asSym()
                    }
                } else {
                    return CommandWithRequiredDecls()
                }
            }

            // "Straightforward" compilation:
            // For each access, get the representative access. The compilation routines
            // will generate _new_ accesses using the representative access to produce a _new_ memory bytemap.
            // i.e. if we have two stores
            //   Mem[p] = v1
            //   Mem[q] = v2
            // then we will rewrite to (roughly)
            //   Mem_{rep(p).id()} = v1
            //   Mem_{rep(q).id()} = v2
            // If the accesses are disjoint, this will result in updates to distinct map variables
            for ((where, _) in accesses) {
                when (val c = g.elab(where).cmd) {
                    is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                        val width = c.meta[WASM_MEMORY_OP_WIDTH]!!
                        val (absLoc, shape) = pointerAndShape(where, c.loc, width) ?: continue
                        val cmds = mergeMany(
                            compileMemLoad(
                                c.lhs,
                                c.loc,
                                BigInteger.ZERO,
                                absLoc.base,
                                shape,
                                width
                            ),
                            stackPointers.interpret(where, c.loc).rightOrNull().addSideCondition(c.loc)
                        )
                        p.addVarDecls(cmds.varDecls)
                        p.replaceCommand(where, cmds.cmds)
                    }

                    is TACCmd.Simple.AssigningCmd.ByteStore -> {
                        val width = c.meta[WASM_MEMORY_OP_WIDTH]!!
                        val (absLoc, shape) = pointerAndShape(where, c.loc, width) ?: continue
                        val cmds = mergeMany(
                            compileMemStore(
                                c.loc,
                                BigInteger.ZERO,
                                c.value,
                                absLoc.base,
                                shape,
                                width
                            ),
                            stackPointers.interpret(where, c.loc).rightOrNull().addSideCondition(c.loc)
                        )

                        p.addVarDecls(cmds.varDecls)
                        p.replaceCommand(where, cmds.cmds)
                    }

                    is TACCmd.Simple.ByteLongCopy -> {
                        val unit = c.length.asConst.gcd(8.toBigInteger()).safeAsInt()
                        // For each chunk,
                        // compile the read, then compile the write...
                        val srcLoc = c.srcOffset
                        val dstLoc = c.dstOffset

                        val srcStackPointer = stackPointers
                            .interpret(where, srcLoc)
                            .rightOrNull()
                            ?.tryAs<PointerValue.Stack>()

                        val dstStackPointer = stackPointers
                            .interpret(where, dstLoc)
                            .rightOrNull()
                            ?.tryAs<PointerValue.Stack>()

                        if (srcStackPointer == null && dstStackPointer == null) {
                            continue
                        }

                        val cmds = mutableListOf<CommandWithRequiredDecls<TACCmd.Simple>>()

                        cmds.add(srcStackPointer.addSideCondition(srcLoc))
                        cmds.add(dstStackPointer.addSideCondition(dstLoc))

                        for (i in (0 ..< c.length.asConst.safeAsInt()/unit)) {
                            val t = TACKeyword.TMP(Tag.Bit256)


                            val read = if (srcStackPointer != null) {
                                val (srcAbsLoc, srcShape) = pointerAndShape(where, srcLoc, unit, (unit*i).toBigInteger()) ?: continue
                                compileMemLoad(
                                    dst = t,
                                    loc = srcLoc,
                                    off = (unit*i).toBigInteger(),
                                    absLoc = srcAbsLoc.base,
                                    unit = srcShape,
                                    width = unit,
                                )
                            } else {
                                TXF { srcLoc add (unit * i).toBigInteger() }.letVar { ptr ->
                                    CommandWithRequiredDecls(
                                        TACCmd.Simple.AssigningCmd.ByteLoad(
                                            lhs = t,
                                            loc = ptr.s,
                                            base = TACKeyword.MEMORY.toVar()
                                        )
                                    )
                                }
                            }.merge(t)


                            val write = if (dstStackPointer != null) {
                                // shouldn't continue..
                                val (dstAbsLoc, dstShape) = pointerAndShape(where, dstLoc, unit, (unit*i).toBigInteger()) ?: continue
                                compileMemStore(
                                    loc = dstLoc,
                                    v = t,
                                    off = (unit * i).toBigInteger(),
                                    absLoc = dstAbsLoc.base,
                                    unit = dstShape,
                                    width = unit,
                                )
                            } else {
                                TXF { dstLoc add (unit * i).toBigInteger() }.letVar { ptr ->
                                    memStore(ptr, t.asSym())
                                }
                            }

                            cmds.add(read)
                            cmds.add(write)
                        }
                        val new = mergeMany(cmds)
                        p.addVarDecls(new.varDecls)
                        p.replaceCommand(where, new.cmds)
                    }

                    else -> `impossible!`
                }

            }
        }
    }
}
