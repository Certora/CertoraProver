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

package analysis.loop

import datastructures.stdcollections.*
import analysis.*
import analysis.smtblaster.IBlaster
import analysis.worklist.SimpleWorklist
import instrumentation.transformers.DSA_BLOCK_END
import instrumentation.transformers.DSA_BLOCK_START
import parallel.ParallelPool
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACMeta.MEM_INCARN
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.util.IdentityHashMap

/**
 * Loops are "summarized" by providing a single summary of an iteration of the loop.
 *
 * Each summary gives the effects on (live) stack variables after a single execution of the loop
 * as a symbolic expression over the names of values pre-execution of the loop. Generally these
 * expressions are "regular" TACExpr, but may fall back on "uninterpreted" functions when a precise
 * approximation is required.
 *
 * Additionally, the loop summary will contain the memory effects of the loop body.
 *
 * These memory effects are either a single write executed during the loop body, or
 * an over-approximation of the effects of writes that occur within a nested loop.
 *
 *
 * [includeFixups] indicates whether the summarization should try to give "special handling" for fixup blocks.
 * It is expected this arugment will be removed soon to remove this special handling for all users.
 */
class LoopSummarization(private val g: TACCommandGraph, private val blaster: IBlaster, loops: Collection<Loop>, val includeFixups: Boolean = true) {

    private val revertBlocks = g.cache.revertBlocks

    private val summaryCache = mutableMapOf<Loop, LoopIterationSummary?>()

    private val loopByHead = loops.map {
        it.head to it
    }.toMap()

    interface MemoryAccessEvent

    sealed class MemoryMutation : MemoryAccessEvent {
        data class MemoryWrite(override val index: TACExpr, val value: TACExpr) : MemoryIndexEvent, MemoryMutation()

        /**
         * @param nested is the summary of the nested loop
         * @param iterationVar is the symbolic name for the total number of loop iterations executed, free within the writes
         * @param writes The write effects of the loop, summarized using IteratedValueSummaries
         */
        data class NestedLoop(val nested: LoopIterationSummary,
                              val writes: List<MemoryMutation>
        ) : MemoryAccessEvent, MemoryMutation()

        data class MemoryMutationOver(val v: List<TACExpr>) : MemoryMutation()

        data class MemoryCopy(override val index: TACExpr, val range: TACExpr) : MemoryMutation(), MemoryIndexEvent
    }

    interface MemoryIndexEvent {
        val index: TACExpr
    }

    data class MemoryRead(val base: TACSymbol.Var, override val index: TACExpr) : MemoryIndexEvent

    data class WriteTarget(
            val sym: TACSymbol.Var,
            val incarn: Int
    )

    data class LoopIterationSummary(
            val iterationEffect: Map<TACSymbol.Var, TACExpr>,
            val exitCondition: TACExpr,
            val reachingWrites: Map<WriteTarget, List<MemoryMutation>>,
            val memoryReads: Map<CmdPointer, MemoryRead>,
            val isDoLoop: Boolean,
            val loop: Loop
    ) {
        fun getAllWrites() : List<MemoryMutation> {
            return reachingWrites.values.maxByOrNull { it.size }.orEmpty()
        }

        /** @return variables x and effects eff_x s.t. (x, eff_x) \in [iterationEffect] and x != eff_x */
        fun nonIdentityIterationEffects(): Map<TACSymbol.Var, TACExpr> {
            return iterationEffect.filter { (x,eff) ->
                !eff.equivSym(x)
            }
        }
    }

    private val fixupSummarizer = CombinedPostWriteFixupSummarization(blaster = blaster, graph = g)

    data class ExitInfo(
        val exitCondition: TACExpr,
        val isDoLoop: Boolean
    )

    private data class BranchSummary(
        val write: List<MemoryMutation>,
        val read: Map<CmdPointer, MemoryRead>,
        val output: Map<TACSymbol.Var, TACExpr>,
        val exitCondition: ExitInfo?
    )

    private fun collectIncreasing(v: TACExpr) : Set<TACSymbol.Var> {
        return when(v) {
            is TACExpr.Vec.Mul,
            is TACExpr.Vec.Add -> {
                (v as TACExpr.Vec).ls.flatMapTo(mutableSetOf()) {
                    collectIncreasing(it)
                }
            }
            is TACExpr.Apply -> {
                if(v.f == monotoneSummary) {
                    v.ops.flatMapTo(mutableSetOf()) {
                        collectIncreasing(it)
                    }
                } else {
                    setOf()
                }
            }
            is TACExpr.Sym.Var -> {
                setOf(v.s)
            }
            else -> setOf()
        }
    }

    private fun computeAllWriteIndices(l: List<MemoryMutation>) : List<Set<TACSymbol.Var>> {
        return l.flatMap {
            when(it) {
                is MemoryMutation.MemoryCopy,
                is MemoryMutation.MemoryWrite -> {
                    listOf(
                        collectIncreasing((it as MemoryIndexEvent).index)
                    )
                }
                is MemoryMutation.MemoryMutationOver -> {
                    listOf(it.v.flatMapTo(mutableSetOf()) {
                        collectIncreasing(it)
                    })
                }
                is MemoryMutation.NestedLoop -> computeAllWriteIndices(it.writes)
            }
        }
    }


    private fun summarizeStraightLine(depth: Int, l: Loop, _start: CmdPointer, endPointer: CmdPointer, inlineConst: Boolean) : BranchSummary? {
        val preState = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
        var mappingId = 0
        val map = mutableMapOf<TACSymbol.Var, TACExpr>()
        fun lookup(v: TACSymbol.Var): TACExpr {
            return map.computeIfAbsent(v) { _ ->
                val newId = "loop_entrance_${mappingId++}"
                val symbolicPreVar = TACSymbol.Var(namePrefix = newId, tag = v.tag)
                preState[symbolicPreVar] = v
                TACExpr.Sym(symbolicPreVar)
            }
        }

        if(inlineConst) {
            val mustBeConstantAnalysis = MustBeConstantAnalysis(g, object : NonTrivialDefAnalysis(g, g.cache.def) {
                override val defaultStopAt: ((TACSymbol.Var) -> Boolean) = { currentVar -> currentVar == TACKeyword.MEM64.toVar() }
            })
            l.body.flatMap {
                g.elab(it).commands.flatMap {
                    it.cmd.getFreeVarsOfRhs().mapNotNull { v ->
                        mustBeConstantAnalysis.mustBeConstantAt(it.ptr, v)?.let {
                            v to it
                        }
                    }
                }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }.filterValues {
                it.size == 1
            }.mapValues {
                it.value.first()
            }.forEach { (t, u) ->
                map[t] = u.asTACSymbol().asSym()
            }
        }

        val substitution = object : DefaultTACExprTransformer() {
            override fun transform(exp: TACExpr): TACExpr {
                return if (exp is TACExpr.Sym.Var) {
                    val v = exp.s
                    lookup(v)
                } else {
                    super.transform(exp)
                }
            }
        }

        var it = g.elab(_start)
        val reachingWrites = mutableListOf<MemoryMutation>()
        fun interpSym(loc: TACSymbol): TACExpr {
            return when (loc) {
                is TACSymbol.Const -> TACExpr.Sym(loc)
                is TACSymbol.Var -> lookup(loc)
            }
        }

        var isDoLoop = false
        var memIncarn = 0
        var exitCond: TACExpr? = null
        val memoryReads = mutableMapOf<CmdPointer, MemoryRead>()

        // Make sure we only transform each Select once.  This is an IdentityHashMap so that we don't do full equality
        // comparisons between TACExpr instances, which would be a) slow, and b) broken (because of the weird equality
        // semantics of TACSymbol.Var).
        val selectMap = IdentityHashMap<TACExpr.Select, TACExpr.Select>()

        val revMapper = object : DefaultTACExprTransformer() {
            override fun transform(exp: TACExpr): TACExpr {
                return if(exp is TACExpr.Sym.Var) {
                    check(exp.s in preState)
                    TACExpr.Sym(preState[exp.s]!!)
                } else if(exp is TACExpr.Select) {
                    selectMap.getOrPut(exp) {
                        TACExpr.Select(
                            base = if(exp.base.tag == Tag.WordMap){ this.transform(exp.base) } else { exp.base },
                            loc = this.transform(exp.loc)
                        )
                    }
                } else {
                    super.transform(exp)
                }
            }
        }

        while (true) {
            val cmd = it.cmd
            if (cmd is TACCmd.Simple.AssigningCmd) {
                when(cmd) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        /**
                         * Workaround for a very weird pattern in via-ir generated code (see https://certora.atlassian.net/browse/CERT-6554)
                         * where the compiler will move stack slots around, swapping and such within a loop body
                         * to make DSA think it needs a confluence variable. Thus, we'll see
                         *
                         * ```
                         * y = z
                         * while(x < y) {
                         *    ...
                         *    y = z
                         * }
                         * ```
                         *
                         * where z isn't modified within the loop. There might be a way to fix this within DSA but I don't
                         * care to.
                         */
                        if(cmd.rhs is TACExpr.Sym.Var && revMapper.transform(substitution.transform(cmd.rhs)) == cmd.rhs && cmd.lhs in g.cache.gvn.equivBefore(it.ptr, cmd.rhs.s)) {
                            map[cmd.lhs] = substitution.transform(cmd.lhs.asSym())
                        } else {
                            map[cmd.lhs] = substitution.transform(cmd.rhs)
                        }
                    }
                    is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                        if(depth > 0) {
                            map[cmd.lhs] = TACExpr.Apply(
                                f = havocSummary,
                                ops = listOf(),
                                tag = Tag.Bit256
                            )
                        } else {
                            val baseVar = if (cmd.base == TACKeyword.MEMORY.toVar()) {
                                TACKeyword.MEMORY.toVar().withMeta(MEM_INCARN, memIncarn)
                            } else {
                                cmd.base.withMeta(MEM_INCARN, 0)
                            }
                            val loc = interpSym(cmd.loc)
                            memoryReads[it.ptr] = MemoryRead(
                                baseVar, revMapper.transform(loc)
                            )
                            map[cmd.lhs] = TACExpr.Select(
                                base = TACExpr.Sym(baseVar),
                                loc = loc
                            )
                        }
                    }
                    is TACCmd.Simple.AssigningCmd.ByteStore -> {
                        if (cmd.base == TACKeyword.MEMORY.toVar()) {
                            reachingWrites.add(MemoryMutation.MemoryWrite(
                                index = revMapper.transform(interpSym(cmd.loc)),
                                value = revMapper.transform(interpSym(cmd.value))
                            ))
                            memIncarn++
                        }
                    }
                    is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                        if (cmd.base == TACKeyword.MEMORY.toVar()) {
                            reachingWrites.add(MemoryMutation.MemoryWrite(
                                index = revMapper.transform(interpSym(cmd.loc)),
                                value = revMapper.transform(interpSym(cmd.value))
                            ))
                            memIncarn++
                        }
                    }
                    is TACCmd.Simple.AssigningCmd.WordLoad -> {
                        map[cmd.lhs] = TACExpr.Select(
                            interpSym(cmd.base), interpSym(cmd.loc)
                        )
                    }
                    is TACCmd.Simple.AssigningCmd.AssignSha3Cmd,
                    is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd,
                    is TACCmd.Simple.AssigningCmd.AssignMsizeCmd,
                    is TACCmd.Simple.AssigningCmd.AssignGasCmd,
                    is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> {
                        map[cmd.lhs] = TACExpr.Apply(
                            havocSummary,
                            ops = listOf(),
                            tag = Tag.Bit256
                        )
                    }
                }
            } else if(cmd is TACCmd.Simple.ByteLongCopy) {
                if (cmd.dstBase == TACKeyword.MEMORY.toVar()) {
                    reachingWrites.add(MemoryMutation.MemoryCopy(
                        index = revMapper.transform(interpSym(cmd.dstOffset)),
                        range = revMapper.transform(interpSym(cmd.length))
                    ))
                    memIncarn++
                }
            } else if(cmd is TACCmd.Simple.CallCore) {
                if(cmd.outBase != TACKeyword.MEMORY.toVar() || cmd.outOffset !is TACSymbol.Var) {
                    return null
                }
                reachingWrites.add(
                    MemoryMutation.MemoryMutationOver(
                        listOf(lookup(cmd.outOffset))
                    )
                )
                memIncarn++
            }
            val succ = g.pathConditionsOf(it.ptr).filter {
                it.key.block !in revertBlocks
            }
            val nxtVar : LTACCmd
            if (succ.size == 1) {
                val (dst, _) = succ.entries.first()
                if (dst.block !in l.body) {
                    return null
                }
                nxtVar = g.elab(dst)
            } else if (succ.size != 2) {
                return null
            } else {
                val (exit, loop) = succ.entries.partition {
                    it.key.block !in l.body
                }
                when {
                    exit.size == 1 && loop.size == 1 -> {
                        if(depth > 0 || exitCond != null) {
                            return null
                        }
                        when (val eCond = exit.first().value) {
                            is PathCondition.NonZero -> {
                                exitCond = interpSym(eCond.v)
                            }
                            is PathCondition.EqZero -> {
                                exitCond = TACExpr.UnaryExp.LNot(interpSym(eCond.v))
                            }
                            else -> {}
                        }
                        isDoLoop = it.ptr.block == l.tail || (loop.singleOrNull()?.key?.block?.let {
                            it == l.tail && g.elab(it).commands.let { cmds ->
                                val fst = cmds.first().cmd
                                val lst = cmds.last().cmd
                                fst is TACCmd.Simple.AnnotationCmd && fst.annot.k == DSA_BLOCK_START && lst is TACCmd.Simple.AnnotationCmd && lst.annot.k == DSA_BLOCK_END
                            }
                        } == true)
                        nxtVar = loop.first().key.let(g::elab)
                    }
                    loop.size == 2 -> {
                        val domFrontier = succ.map { it.key.block }.monadicMap {
                            val visited = mutableSetOf<NBId>()
                            val dom = g.cache.domination
                            val frontier = mutableSetOf<NBId>()
                            var fail = false
                            SimpleWorklist.iterateWorklist(listOf(it)) { curr, nxt ->
                                if(!visited.add(curr)) {
                                    return@iterateWorklist
                                }
                                for(s in g.succ(curr)) {
                                    if(s in g.cache.revertBlocks) {
                                        continue
                                    }
                                    if(s !in l.body) {
                                        fail = true
                                        return@iterateWorklist
                                    }
                                    if(!dom.dominates(it, s)) {
                                        frontier.add(s)
                                    } else if(s !in visited) {
                                        nxt.add(s)
                                    }
                                }
                            }
                            it `to?` frontier.takeIf { !fail }
                        }?.monadicMap { (k,v) ->
                            k `to?` v.singleOrNull()
                        }?.toList()?.let { domFrontier ->
                            val joinPoint = domFrontier.map { it.second }.uniqueOrNull()
                            if(joinPoint == null) {
                                check(domFrontier.size == 2)
                                // our last hope, is one successor the dominance frontier of the other?
                                domFrontier.filterIndexed { ind, (_, f) ->
                                    val otherInd = ind xor 1
                                    f == domFrontier[otherInd].first
                                }.singleOrNull()?.second
                            } else {
                                joinPoint
                            }
                        } ?: return null
                        // summarize the branches
                        val mut = succ.keysMatching { key, _ ->
                            key.block != domFrontier
                        }.monadicMap {
                            summarizeStraightLine(
                                depth + 1,
                                l = l,
                                _start = it,
                                endPointer = g.elab(domFrontier).commands.first().ptr,
                                inlineConst = inlineConst
                            )
                        } ?: return null
                        val toMut = mutableMapOf<TACSymbol.Var, Boolean>()
                        val commonWriteSource = mutableListOf<Set<TACSymbol.Var>>()
                        mut.forEach {
                            it.output.forEach { k, e ->
                                if(!e.equivSym(k)) {
                                    toMut.merge(k, isMonotoneTransformerFor(e) {
                                        it == k
                                    }) { a, b -> a && b }
                                }
                            }
                            commonWriteSource.addAll(computeAllWriteIndices(it.write))
                        }
                        if(commonWriteSource.isNotEmpty()) {
                            val inAllWrites = commonWriteSource.reduce { a, b ->
                                a.intersect(b)
                            }
                            if(inAllWrites.isEmpty()) {
                                return null
                            }
                            reachingWrites.add(MemoryMutation.MemoryMutationOver(inAllWrites.map {
                                revMapper.transform(lookup(it))
                            }))
                            memIncarn++
                        }
                        for((k, isIncreasing) in toMut) {
                            map[k] = if(isIncreasing) {
                                TACExpr.Apply(
                                    f = monotoneSummary,
                                    ops = listOf(
                                        lookup(k)
                                    ),
                                    tag = Tag.Bit256
                                )
                            } else {
                                TACExpr.Apply(
                                    f = havocSummary,
                                    ops = listOf(),
                                    tag = Tag.Bit256
                                )
                            }
                        }
                        nxtVar = g.elab(domFrontier).commands.first()
                    }
                    else -> return null
                }
            }
            if(nxtVar.ptr == endPointer) {
                map -= (map.keys - g.cache.lva.liveVariablesAfter(it.ptr))
            }
            it = nxtVar
            while (it.ptr != endPointer && it.ptr.block in loopByHead && it.ptr.block != l.head) {
                val nestedSummary = summarizeLoop(loopByHead[it.ptr.block]!!, inlineConst) ?: return null
                val nestedLoop = loopByHead[it.ptr.block]!!
                val writeEffects = mutableListOf<MemoryMutation>()
                nestedSummary.getAllWrites().let { mms ->
                    /*
                        This gets confusing, so let's review. We have the following loop structure:

                        loop A {
                          loop B {
                            loop C {

                            }
                          }
                        }

                        And we are now summarizing loop A, and have encountered loop B.

                        Let's first describe how C is summarized within B's summary.
                        On each iteration of B, C is executed some number of times, writing at some indices. The value
                        of an index at iteration k is represented by applying the defining equation of the index
                        (which is w.r.t the value at the START of the kth iteration) to a symbolic term representing
                        the initial value on the kth iteration.

                        This "kth iteration value" can be thought of as as being simply "apply the effect loop k times to the value from before
                        iteration 0". This "apply the effect k times" is represented by a LoopSummaryFunction;
                        it takes two arguments, some name for k (bound by iterationVar in the containing nestedloop object)
                        and the term for the initial value at entrance to the loop (i.e., the value at iteration 0).
                        Note that this loop summary function is not
                        a true function as the ultimate value on the kth iteration may depend on many possible source values.

                        The value of any variable at the beginning of C can thus be represented as an expression
                        over the values of variables at the beginning of SOME iteration of B. So ultimately, for a write
                        in the body of C we have e1(f2(e3(x1, x2), k)) where:
                        + k is the symbolic name for "the kth iteration"
                        + e3 is the expression in terms of variables x1, x2, ... at the beginning of an iteration of loop b
                        + f2 is the loop summary function abstracting that the initial value e3 has been transformed k times, and
                        + e1 is the term transforming the value at the start of an iteration of C to the actual write index

                        Now we simply perform the wrapping one more time. Let e4 be the transformation from the value x3 to the value
                        of x1 at the beginning of loop B, and similarly for e5, x4, and x2. Then we have the write summary
                        expressed in loop A as:

                        e1(f2(e3(f6(e4(x3), k'), f7(e5(x4), k')), k))

                        notice that we have wrapped the values x1 and x2 with fresh "loop summary functions" f6 and f7.

                        Thus, to translate a nestedloop summary in loop B, we have to first:
                        a) compute the value of free variables at the entrance to the loop in terms of variables at the entrance
                        to the outer loop, and then
                        b) abstract these values via the application of a loop summary function
                     */
                    mms.mapTo(writeEffects) { mm ->
                        mapToOuterLoop(mm) {
                            val iteratedSummary =
                                if(computeIsIncreasing(nestedSummary, it)) {
                                    monotoneSummary
                                } else {
                                    havocSummary
                                }
                            // iterated start is f6 in the above, it indicates we are taking the value w.r.t
                            // the kth iteration
                            // now get the value of exp.s at the entrance to this loop, this is e5(x4) or e4(x3)
                            val entrForm = revMapper.transform(interpSym(it))
                            // now apply the summary function to this initial value
                            TACExpr.Apply(
                                f = iteratedSummary, ops = listOf(
                                    entrForm
                                ),
                                tag = Tag.Bit256
                            )
                        }
                    }
                }
                if (writeEffects.isNotEmpty()) {
                    reachingWrites.add(
                        MemoryMutation.NestedLoop(
                            writes = writeEffects,
                            nested = nestedSummary
                        )
                    )
                    memIncarn++
                }
                for ((v, _) in nestedSummary.nonIdentityIterationEffects()) {
                    val eff = if (computeIsIncreasing(nestedSummary, v)) {
                        monotoneSummary
                    } else {
                        havocSummary
                    }
                    map[v] = TACExpr.Apply(
                        f = eff,
                        ops = listOf(interpSym(v)),
                        tag = Tag.Bit256
                    )
                }
                it = nestedLoop.body.flatMap {
                    g.succ(it) - nestedLoop.body
                }.filter {
                    it !in revertBlocks
                }.singleOrNull()?.let(g::elab)?.commands?.first() ?:
                        return null
                // check if this loop has a post-write fixup
                val postWriteFixups = if(includeFixups) {
                    ParallelPool.globalPool.run(ArrayLoopSummarization(blaster).isArrayWriteStride(nestedSummary)).mapNotNull {
                        ParallelPool.globalPool.run(fixupSummarizer.isPostWriteFixup(nestedLoop, nestedSummary, it, g))
                    }
                } else {
                    listOf()
                }

                if(postWriteFixups.size == 1) {
                    when (val fixup = postWriteFixups.first()) {
                        is CommonFixupReasoning.PostWriteFixup.ConditionalFixup -> {
                            if(!l.body.containsAll(fixup.fixupBlocks) || fixup.succBlock !in l.body) {
                                continue
                            }
                            // collect mutated variables within the block
                            val mut = fixup.fixupBlocks.flatMap {
                                g.elab(it).commands.mapNotNull {
                                    if(it.cmd is TACCmd.Simple.AssigningCmd && it.cmd !is TACCmd.Simple.AssigningCmd.ByteStore) {
                                        it.cmd.lhs
                                    } else {
                                        null
                                    }
                                }
                            }.toSet()
                            val fixupSucc = g.elab(fixup.succBlock).commands.first()
                            if(g.cache.lva.liveVariablesBefore(ptr = fixupSucc.ptr).containsAny(mut)) {
                                continue
                            }
                            it = fixupSucc
                        }

                        is CommonFixupReasoning.PostWriteFixup.SplitFixup -> {
                            if (fixup.finalWrite.block !in l.body) {
                                continue
                            }
                            val mut = g.iterateUntil(fixup.finalWrite).mapNotNull {
                                if(it.cmd is TACCmd.Simple.AssigningCmd && it.cmd !is TACCmd.Simple.AssigningCmd.ByteStore) {
                                    it.cmd.lhs
                                } else {
                                    null
                                }
                            }.toSet()
                            if(g.cache.lva.liveVariablesAfter(ptr = fixup.finalWrite).containsAny(mut)) {
                                continue
                            }
                            it = g.elab(
                                g.succ(fixup.finalWrite).singleOrNull()
                                    ?: error("Write does not have exactly one successor: ${fixup.finalWrite}")
                            )
                        }
                    }
                }
            }
            if (it.ptr == endPointer) {
                break
            }
        }
        return BranchSummary(
            write = reachingWrites,
            output = map.filter { it.value !is TACExpr.Sym.Const }.mapValues {
                revMapper.transform(it.value).simplify()
            },
            exitCondition = if(depth == 0) {
                ExitInfo(
                    exitCondition = revMapper.transform(exitCond ?: return null),
                    isDoLoop
                )
            } else null,
            read = memoryReads
        )
    }

    fun TACExpr.simplify() : TACExpr = when(this) {
        is TACExpr.Vec.Add -> {
            val ops = this.ls.map { it.simplify() }
            val (const, other) = ops.partition {
                it is TACExpr.Sym.Const
            }
            if(const.size > 1) {
                val lifted = const.sumOf {
                    (it as TACExpr.Sym.Const).s.value
                }.let(TACSymbol::Const).let(TACExpr.Sym::Const)
                if(other.isEmpty()) {
                    lifted
                } else {
                    this.copy(ls = other + lifted)
                }
            } else {
                this.copy(ls = ops)
            }
        }
        else -> this
    }

    fun summarizeLoop(l: Loop, inlineConst: Boolean = true): LoopIterationSummary? {
        val cached = synchronized(summaryCache) {
            summaryCache[l]
        }
        if (cached != null) {
            return cached
        }
        val headPtr = g.elab(l.head).commands.first().ptr
        val branch = summarizeStraightLine(depth = 0, endPointer = headPtr, _start = headPtr, l = l, inlineConst = inlineConst)?.takeIf {
            it.exitCondition != null
        } ?: return null
        val incarnationMap = mutableMapOf<WriteTarget, List<MemoryMutation>>(
                getWriteTarget(TACKeyword.MEMORY.toVar().withMeta(MEM_INCARN, 0)) to listOf()
        )
        val memIncarn = branch.write.size
        for (i in 0..memIncarn) {
            incarnationMap[getWriteTarget(TACKeyword.MEMORY.toVar().withMeta(MEM_INCARN, i))] = branch.write.subList(0, i)
        }
        return LoopIterationSummary(
                exitCondition = branch.exitCondition!!.exitCondition,
                iterationEffect = branch.output,
                reachingWrites = incarnationMap,
                memoryReads = branch.read,
                isDoLoop = branch.exitCondition.isDoLoop,
                loop = l
        )
    }

    private fun mapToOuterLoop(m: MemoryMutation, mapper: (TACSymbol.Var) -> TACExpr): MemoryMutation {
        val loopTranslator = object : DefaultTACExprTransformer() {
            override fun transform(exp: TACExpr): TACExpr {
                return if(exp is TACExpr.Sym.Var) {
                    mapper(exp.s)
                } else if(exp is TACExpr.Select) {
                    return TACExpr.Select(
                            base = exp.base,
                            loc = this.transform(exp.loc)
                    )
                } else {
                    super.transform(exp)
                }
            }
        }
        return when(m) {
            is MemoryMutation.MemoryWrite -> {
                MemoryMutation.MemoryWrite(
                        index = loopTranslator.transform(m.index),
                        value = loopTranslator.transform(m.value)
                )
            }
            is MemoryMutation.NestedLoop -> {
                MemoryMutation.NestedLoop(
                        nested = m.nested,
                        writes = m.writes.map {
                            mapToOuterLoop(it, mapper)
                        }
                )
            }
            is MemoryMutation.MemoryCopy -> {
                MemoryMutation.MemoryCopy(
                        index = loopTranslator.transform(m.index),
                        range = loopTranslator.transform(m.range)
                )
            }
            is MemoryMutation.MemoryMutationOver -> {
                MemoryMutation.MemoryMutationOver(
                    m.v.map {
                        loopTranslator.transform(it)
                    }
                )
            }
        }
    }

    private fun computeIsIncreasing(nestedSummary: LoopIterationSummary, key: TACSymbol.Var): Boolean {
        return nestedSummary.iterationEffect[key]?.let { summary ->
            isMonotoneTransformerFor(summary) {
                it == key
            }
        } ?: true
    }

    companion object {
        val monotoneSummary = TACExpr.TACFunctionSym.Adhoc("Monotone")
        val havocSummary = TACExpr.TACFunctionSym.Adhoc("Havoc")

        fun isMonotoneTransformerFor(
            expr: TACExpr,
            baseCase: (TACSymbol.Var) -> Boolean
        ) : Boolean {
            if (expr is TACExpr.Sym) {
                return if (expr is TACExpr.Sym.Const) {
                    false
                } else {
                    check(expr is TACExpr.Sym.Var)
                    baseCase(expr.s)
                }
            }
            return when (expr) {
                is TACExpr.AnnotationExp<*> ->
                    isMonotoneTransformerFor(expr.o, baseCase)
                is TACExpr.Vec -> {
                    when (expr) {
                        is TACExpr.Vec.Add,
                        is TACExpr.Vec.Mul -> {
                            expr.ls.any {
                                isMonotoneTransformerFor(it, baseCase)
                            }
                        }
                        else -> false
                    }
                }
                is TACExpr.BinOp,
                is TACExpr.BinRel,
                is TACExpr.BinBoolOp -> false
                is TACExpr.Apply -> {
                    val fn = expr.f
                    if(fn != monotoneSummary) {
                        return false
                    }
                    expr.ops.all {
                        isMonotoneTransformerFor(it, baseCase)
                    }
                }
                is TACExpr.Sym -> error("impossible?")
                is TACExpr.SimpleHash,
                is TACExpr.Unconstrained,
                is TACExpr.TernaryExp.Ite,
                is TACExpr.TernaryExp.MulMod,
                is TACExpr.TernaryExp.AddMod,
                is TACExpr.QuantifiedFormula,
                is TACExpr.UnaryExp,
                is TACExpr.Select,
                is TACExpr.MapDefinition,
                is TACExpr.Store,
                is TACExpr.MultiDimStore,
                is TACExpr.LongStore,
                is TACExpr.StructAccess,
                is TACExpr.StructConstant -> false
            }
        }

        fun getWriteTarget(s: TACSymbol.Var) = WriteTarget(
                s,
                s.meta.find(MEM_INCARN)!!
        )
    }
}
