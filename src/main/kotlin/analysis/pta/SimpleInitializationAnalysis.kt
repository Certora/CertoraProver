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

package analysis.pta

import algorithms.dominates
import algorithms.strictlyDominates
import analysis.*
import analysis.alloc.AllocationAnalysis
import analysis.alloc.AllocationAnalysis.roundUp
import analysis.alloc.AllocationInformation
import analysis.alloc.StorageArrayLengthFinder
import analysis.loop.*
import analysis.numeric.*
import analysis.numeric.linear.*
import analysis.numeric.linear.TermMatching.matches
import analysis.numeric.linear.TermMatching.matchesAny
import analysis.smtblaster.IBlaster
import analysis.smtblaster.Z3BlasterPool
import analysis.worklist.*
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import parallel.ParallelPool
import tac.NBId
import utils.*
import vc.data.*
import vc.data.tacexprutil.TACExprFreeVarsCollector
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.newKeySet

private val logger = Logger(LoggerTypes.INITIALIZATION)

private data class IQ(
    override val x: IntValue,
    override val qual: Set<SelfQualifier<*>>
) : BoundedQualifiedInt<IQ, SelfQualifier<*>, IntValue>, LatticeElem<IQ, IQ>, WithUIntApproximation<IntValue> {
    override fun withQualifiers(x: Iterable<SelfQualifier<*>>): IQ {
        return this.copy(qual = x.toSet())
    }

    override fun withBoundAndQualifiers(lb: BigInteger, ub: BigInteger, quals: Iterable<SelfQualifier<*>>): IQ {
        return IQ(
                x.withUpperBound(ub).withLowerBound(lb),
                qual = quals.toSet()
        )
    }

    override fun join(other: IQ): IQ {
        return IQ(
                x.join(other.x),
                this.qual.intersect(other.qual)
        )
    }

    override fun widen(next: IQ): IQ {
        return IQ(
                x.widen(next.x),
                this.qual.intersect(next.qual)
        )
    }

    companion object {
        val TOP = IQ(x = IntValue.Nondet, qual = setOf())
    }
}

private typealias NumericState = TreapMap<TACSymbol.Var, IQ>

/**
 * The (not so simple) initialization analysis. The initialization analysis computes, for each allocation (as represented
 * in [allocSites]) the unique point where the initialization of the object is complete. The strategy for this is
 * complicated, and depends on the "shape" of the allocated object (see the documentation elsewhere). It also checks
 * that initializations are "well-nested", that is, an allocation of an object B that occurs during the initialization
 * of an object A must complete before A's initialization completes. In other words, there is a stack structure to
 * allocation/initialization, and this is respected throughout program execution.
 *
 * Conceptually, the initialization analysis is actually multiple parallel analyses, one for each allocated object.
 * Each of these per-object analyses (which we call the "inner analysis") traverses the program in program order using
 * a standard analysis loop. However, due to the point above RE: dependencies, the analysis of one object's
 * initialization window may depend on the results of another object's initialization; to wit, if during
 * the initialization of object A we encounter an allocation&initialization of object B, A's analysis
 * must "skip" the code that effects B's allocation. Thus, in order to proceed with the analysis for A,
 * we must first know where B's initialization completes.
 *
 * To accomodate this dependency, initialization analyses that encounter an allocation&initialization of some other object
 * may explicit *suspend* themselves until the "child" analysis completes. Note that if the "child" initialization analysis
 * itself encounters yet another allocation+init, it too must suspend. Thus, the SIA has an "outer" analysis loop,
 * where individual object initialization analyses are analyzed according to their dependencies: i.e., a topologically
 * work queue is drained such that blocked analyses are only scheduled when the child analyses are completed and can
 * communicate back where the containing initialization analysis should resume.
 */
private class SimpleInitializationAnalysisWorker(private val graph: TACCommandGraph, private val allocSites: AllocationInformation, private val blaster: IBlaster) {
    private val revertBlocks = graph.cache.revertBlocks

    private val loops = getNaturalLoops(graph)

    private val loopHeaders = loops.map {
        it.head to it
    }.toMap()

    private val dom = graph.cache.domination

    private val nonTrivialDefAnalysis by lazy {
        NonTrivialDefAnalysis(graph, defAnalysis)
    }

    private val loopSummaries = ConcurrentHashMap<Loop, Optional<LoopSummarization.LoopIterationSummary>>()

    private fun summarizeLoop(l: Loop) : LoopSummarization.LoopIterationSummary? =
        loopSummaries.getOrPut(l) {
            Optional.ofNullable(loopSummarizer.summarizeLoop(l))
        }.orElse(null)

    private val loopSummarizer = LoopSummarization(
            g = graph,
            loops = loops,
            blaster = blaster
    )

    private val loopFixup = CombinedPostWriteFixupSummarization(blaster = blaster, graph = graph)

    /*
     * p2:
    R38 = 0xa9059cbb .certora_configSafeERC20Harness.sol_1/2_SafeERC20.sol:27: token.transfer.selector
    R39 = 0xe0
    R40 = R38<<R39
    ...
    R109 = 0xffffffff00000000000000000000000000000000000000000000000000000000&R40 .certora_configSafeERC20Harness.sol_1/2_SafeERC20.sol:27: abi.encodeWithSelector(token.transfer.selector, to, value)
    R111 = R97+0x20
    R113 = tacM[R111]
    R116 = R113&0xffffffffffffffffffffffffffffffffffffffffffffffffffffffff
    R117 = R116|R109
    tacM[R111:R111+32] = R117
     */
    private val sigWriteMatcher by lazy {
        val lower28ByteMask = BigInteger.TWO.pow(224) - BigInteger.ONE
        val p1 = PatternDSL.build {
            (  Const { n ->
                (lower28ByteMask and n) == BigInteger.ZERO &&
                        (n.shiftLeft(224) != BigInteger.ZERO)
            } or (Var and Const(lower28ByteMask)).commute.locFirst).commute.withAction { a, b ->
                Triple(a.shiftRight(224), b.first, b.second)
            }
        }
        val p2 = PatternDSL.build {
            (
                    ((Const(
                        BigInteger.TWO.pow(32).minus(BigInteger.ONE).shiftLeft(224)
                    ) and (Const shl 0xe0()).first).commute.second.named("sigWriteMatcher-p2-add-highbits")
                            or (Var and Const(lower28ByteMask)).commute.locFirst.named("sigWriteMatcher-p2-add-lowbits"))
                        .named("sigWriteMatcher-p2-add-int")
                    ).commute.named("sigWriteMatcher-p2-add").withAction { a, b ->
                    Triple(a, b.first, b.second)
                }.named("sigWriteMatcher-p2")
        }
        PatternMatcher.Pattern.XOr.orSame(
            "sigWriteMatcher",
            p1,
            p2
        ).let {
            PatternMatcher.compilePattern(graph, it)
        }
    }

    private val plus32Matcher by lazy {
        PatternDSL.build {
            (32() + Var).commute.locSecond
        }.let {
            PatternMatcher.compilePattern(graph, it)
        }
    }

    private data class InitLoopResult(
        val closePoints: Map<AllocationAnalysis.AbstractLocation, AnalysisResult.Complete>,
        val fourByteWrite: Map<CmdPointer, SimpleInitializationAnalysis.FourByteWrite>,
        val failurePoints: Set<CmdPointer>,
        val markLengthReads: Set<CmdPointer>,
        val deadAllocations: Set<AllocationAnalysis.AbstractLocation>,
        val markDefiniteBounds: Set<CmdPointer>,
        val inferredLoopBounds: Map<NBId, BigInteger>
    )

    private fun outerAnalysisLoop(): InitLoopResult {
        val sites = allocSites.abstractAllocations.map { it.value to it.key }.groupBy({it.first}, {it.second})
        val toRet = mutableMapOf<AllocationAnalysis.AbstractLocation, AnalysisResult.Complete>() // completed
        val deadAllocations = mutableSetOf<AllocationAnalysis.AbstractLocation>() // dead ignored allocations (all paths after allocation revert)
        val toSubmit = mutableListOf<Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>>() // initial worklist
        val fourByteWrites = mutableMapOf<CmdPointer, SimpleInitializationAnalysis.FourByteWrite>()
        val failures = allocSites.failureMarkers.toMutableSet()
        val failLocs = mutableSetOf<AllocationAnalysis.AbstractLocation>()
        val globalInvariants = GlobalInvariantAnalysis(3).analyze(graph)

        for((loc, readSites) in sites) {
            if(loc.sort is AllocationAnalysis.Alloc.PackedByteArray) {
                // check for 4 byte write
                val closePoint = graph.iterateBlock(loc.sort.finalWrite).firstMapped {
                    if(it.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                            it.cmd.base == TACKeyword.MEMORY.toVar() &&
                            it.cmd.value is TACSymbol.Var &&
                            it.cmd.loc is TACSymbol.Var) {
                        it.narrow<TACCmd.Simple.AssigningCmd.ByteStore>()
                    } else {
                        null
                    }
                }?.let { byteStore ->
                    sigWriteMatcher.query(byteStore.cmd.value as TACSymbol.Var, byteStore.wrapped).toNullableResult()?.let {
                        byteStore to it
                    }
                }?.let { (store, maskedVal) ->
                    val (plusLoc, added) = plus32Matcher.query(store.cmd.loc as TACSymbol.Var, store.wrapped).toNullableResult() ?: return@let null
                    /* so our index is defined by mem64 + 32 before the write */
                    if(TACKeyword.MEM64.toVar() !in graph.cache.gvn.findCopiesAt(loc.sort.finalWrite, plusLoc.ptr to added) ||
                            plusLoc.ptr.block != loc.sort.finalWrite.block) {
                        return@let null
                    }
                    val d = nonTrivialDefAnalysis.nontrivialDefSingleOrNull(maskedVal.third, maskedVal.second)
                    if(d == null) {
                        return@let null
                    }
                    val maskedDef = graph.elab(d)
                    if(maskedDef.cmd !is TACCmd.Simple.AssigningCmd.ByteLoad ||
                            maskedDef.cmd.base != TACKeyword.MEMORY.toVar() ||
                                    maskedDef.cmd.loc !is TACSymbol.Var) {
                        return@let null
                    }
                    // then we mask a value that we read out of memory. Is it from the same place we read?
                    if(maskedDef.cmd.loc !in graph.cache.gvn.findCopiesAt(maskedDef.ptr, store.ptr to (store.cmd.loc as TACSymbol.Var))) {
                        return@let null
                    }
                    /*
                      Now we have the following:
                      We immediately write in a value at fp + 32 that is defined by masking the lower 28 bytes read from fp + 32 and then
                      or-ing it with some 4 byte constant.
                     */
                    if(store.ptr.pos == graph.elab(store.ptr.block).commands.lastIndex) {
                        return@let null
                    }
                    // then this is a four byte write. remember it
                    fourByteWrites[store.ptr] = SimpleInitializationAnalysis.FourByteWrite(
                            maskedVal.first,
                            store.cmd.loc as TACSymbol.Var
                    )
                    store.ptr.copy(pos = store.ptr.pos + 1)
                } ?: loc.sort.finalWrite
                toRet[loc] = AnalysisResult.Complete(close = closePoint.toBefore(), nested = setOf(), mutated = setOf(), markTop = setOf(), markDefiniteBounds = setOf(), loopBounds = mapOf())
                logger.debug {
                    "Initialization for $loc (a packed byte array) trivially ends at final write ${loc.sort.finalWrite}"
                }
                continue
            }
            if(loc.sort is AllocationAnalysis.Alloc.StorageUnpack) {
                toRet[loc] = AnalysisResult.Complete(
                    close = CmdPointer(loc.sort.closeLoc, 0).toBefore(),
                    nested = setOf(),
                    mutated = setOf(),
                    markTop = setOf(),
                    markDefiniteBounds = setOf(),
                    loopBounds = mapOf()
                )
                continue
            }
            if(loc.sort is AllocationAnalysis.Alloc.ConstantStringAlloc) {
                toRet[loc] = AnalysisResult.Complete(close = graph.succ(loc.sort.dataCopy).singleOrNull()?.toBefore() ?: run {
                    failures.addAll(readSites)
                    failLocs.add(loc)
                    null
                } ?: continue, nested = setOf(), mutated = setOf(), markTop = setOf(), markDefiniteBounds = setOf(), loopBounds = mapOf())
                continue
            }
            // find the "first" read
            val readSort = try {
                readSites.sortedWith { a: CmdPointer, b: CmdPointer ->
                    when {
                        dom.dominates(a, b) -> -1
                        dom.dominates(b, a) -> 1
                        else -> {
                            // This is the *worst*, but kotlin only *sometimes* supports return from lambdas
                            throw CancelSortException("Could not sort $a and $b")
                        }
                    }
                }
            } catch(x: CancelSortException) {
                logger.warn(x) {
                    "While finding first read of $loc with read sites $readSites"
                }
                failures.addAll(readSites)
                continue
            }
            val start = readSort.first()
            if(loc.sort is AllocationAnalysis.Alloc.DynamicBlock || loc.sort is AllocationAnalysis.Alloc.ConstantArrayAlloc) {

                val (eSz, lenDef, lengthReader) = if (loc.sort is AllocationAnalysis.Alloc.DynamicBlock) {
                    val dynBlock: AllocationAnalysis.Alloc.DynamicBlock = loc.sort
                    // our heuristic for storage array copies...
                    val heuristicEnd = checkStorageArrayCopy(dynBlock)
                    if (heuristicEnd != null) {
                        toRet[loc] = AnalysisResult.Complete(
                            close = graph.elab(heuristicEnd).commands.first().ptr.toBefore(),
                            nested = setOf(),
                            mutated = setOf(),
                            markTop = setOf(),
                            markDefiniteBounds = setOf(),
                            loopBounds = mapOf()
                        )
                        continue
                    }

                    // let's check that the length is in scope *somewhere* at this point
                    val copies = graph.cache.gvn.findCopiesAt(start, dynBlock.lengthSym)
                    if(copies.isEmpty()) {
                        logger.info {
                            "Could not find a copy of the array length at first read of $loc at $start, falling back on symbolic"
                        }
                        val nonTrivialDefSite =
                            nonTrivialDefAnalysis.nontrivialDefSingleOrNull(loc.sort.lengthSym.second, loc.sort.lengthSym.first)
                        if (nonTrivialDefSite == null) {
                            logger.warn {
                                "Could not find single definition site for the length @ ${loc.sort.lengthSym}. Giving up"
                            }
                            failures.addAll(readSites)
                            continue
                        }
                        val dom = graph.cache.domination
                        val indexForm = graph.elab(nonTrivialDefSite).cmd
                        Triple(
                            loc.sort.eSz,
                            setOf<LinearAtom>(LinearAtom(LENGTH, loc.sort.eSz)).toRight()
                        ) { x: LTACCmdView<TACCmd.Simple.AssigningCmd> ->
                            if (x.cmd.lhs == loc.sort.lengthSym.second) {
                                if (setOf(nonTrivialDefSite) == setOf(x.ptr)) {
                                    if (graph.elab(nonTrivialDefSite).cmd is TACCmd.Simple.AssigningCmd.ByteLoad) {
                                        LengthReadResult.NON_ALIASING_BYTELOAD
                                    } else {
                                        LengthReadResult.DIRECT
                                    }
                                } else {
                                    LengthReadResult.NONE
                                }
                            } else if (x.cmd::class.java === indexForm::class.java &&
                                x.cmd is TACCmd.Simple.AssigningCmd.ByteLoad &&
                                (x.cmd as TACCmd.Simple.AssigningCmd.ByteLoad).loc is TACSymbol.Var &&
                                (indexForm as TACCmd.Simple.AssigningCmd.ByteLoad).loc is TACSymbol.Var
                            ) {
                                /*
                                  Are we reading from the same index in memory that defines the length?
                                  If so, yeet!
                                 */
                                val loc1 = (x.cmd as TACCmd.Simple.AssigningCmd.ByteLoad).loc
                                val loc2 = indexForm.loc
                                val def1 = nonTrivialDefAnalysis.nontrivialDefSingleOrNull(loc1 as TACSymbol.Var, origin = x.ptr)
                                val def2 = nonTrivialDefAnalysis.nontrivialDefSingleOrNull(
                                    loc2 as TACSymbol.Var,
                                    origin = nonTrivialDefSite
                                )
                                val check = def1 != null && def1 == def2 &&
                                        dom.strictlyDominates(def1, x.ptr) && dom.strictlyDominates(def1, nonTrivialDefSite)
                                if (check) {
                                    LengthReadResult.NON_ALIASING_BYTELOAD
                                } else {
                                    LengthReadResult.NONE
                                }
                            } else {
                                LengthReadResult.NONE
                            }
                        }
                    } else {
                        val linAtom = mutableSetOf<LinearAtom>()
                        copies.mapTo(linAtom) {
                            LinearAtom(LVar.PVar(it), loc.sort.eSz)
                        }
                        if (dynBlock.eSz == EVM_WORD_SIZE && dynBlock.lengthSym.first.block == start.block && dynBlock.lengthSym.first.pos < start.pos &&
                            graph.elab(dynBlock.lengthSym.first).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
                                ?.let { lc ->
                                    val rhs = lc.cmd.rhs
                                    ((rhs is TACExpr.Vec.Mul && rhs.operandsAreSyms() && rhs.ls.any {
                                        it.equivSym(dynBlock.eSz.asTACSymbol())
                                    } && rhs.ls.any {
                                        it.equivSym(dynBlock.lengthSym.second)
                                    }) || (dynBlock.eSz.bitCount() == 1 && rhs is TACExpr.BinOp.ShiftLeft && rhs.o1.equivSym(
                                        dynBlock.lengthSym.second
                                    ) && rhs.o2.equivSym(
                                        dynBlock.eSz.lowestSetBit.toBigInteger().asTACSymbol()
                                    ))) && graph.iterateBlock(lc.ptr).takeWhile {
                                        it.ptr.pos < start.pos
                                    }.none {
                                        it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs == lc.cmd.lhs
                                    }
                                } == true
                        ) {
                            linAtom.add(
                                LinearAtom(
                                    LVar.PVar(
                                        graph.elab(dynBlock.lengthSym.first).narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs
                                    ), BigInteger.ONE
                                )
                            )
                        }
                        Triple(loc.sort.eSz, linAtom.toRight(), null)
                    }
                } else {
                    check(loc.sort is AllocationAnalysis.Alloc.ConstantArrayAlloc)
                    Triple(loc.sort.eSz, loc.sort.constSize.toLeft(), null)
                }

                val inv = LinearEquality.build {
                    val ret = mutableListOf<LinearEquality>()
                    val lenTerms = lenDef.toValue({
                        listOf(LinearTerm(listOf(), it * eSz))
                    }) {
                        it.map {
                            LinearTerm(
                                listOf(it)
                            )
                        }
                    }
                    lenTerms.forEach {
                        ret.add(WRITE + it `=` END_BLOCK)
                        ret.add(LVar.PVar(TACKeyword.MEM64.toVar()) + it + 0x20 `=` END_BLOCK)
                    }
                    ret
                }.toTreapSet() + globalInvariants.getBeforeLocation(start)
                logger.debug {
                    "Initial invariants for $loc"
                }
                inv.forEach {
                    logger.debug { "$it" }
                }
                logger.debug { "(DONE)" }
                val lenVars = lenDef.toValue({
                    setOf<TACSymbol.Var>()
                }, { lvarSet ->
                    lvarSet.mapNotNull { (fst, snd) ->
                        (fst as? LVar.PVar)?.v?.takeIf {
                            snd == eSz
                        }
                    }.toSet()
                })
                toSubmit.add(
                    loc to AnalysisResult.Suspend(
                        ir = listOf(),
                        remaining = listOf(start),
                        state = mutableMapOf(
                            start to getInitialState(inv,
                                constSize = lenDef.toValue({ it }, { null }),
                                eSz = eSz,
                                nondetInts = lenVars,
                                quals = (listOf(
                                    TACKeyword.MEM64.toVar() to Roles.ARRAY_START
                                ) + lenVars.map {
                                    it to Roles.LENGTH
                                }).toMap(),
                                isLengthRead = lengthReader
                            )
                        ),
                        dep = setOf(),
                        mutated = setOf(),
                        markTop = setOf(),
                        markDefiniteBounds = setOf(),
                        loopBounds = treapMapOf()
                    )
                )
            } else {
                check(loc.sort is AllocationAnalysis.Alloc.ConstBlock)
                val sz = loc.sort.sz
                val inv = LinearEquality.build {
                    listOf(WRITE, LVar.PVar(TACKeyword.MEM64.toVar())).map { base ->
                        base + sz `=` END_BLOCK
                    }
                }.toTreapSet() + globalInvariants.getBeforeLocation(start)
                logger.debug {
                    "Initial invariants for $loc"
                }
                inv.forEach {
                    logger.debug { "$it" }
                }
                logger.debug { "(DONE)" }
                toSubmit.add(
                        loc to AnalysisResult.Suspend(
                            ir = listOf(),
                            remaining = listOf(start),
                            state = mutableMapOf(
                                    start to getInitialState(inv, eSz = null, constSize = null)
                            ),
                            dep = setOf(),
                            mutated = setOf(),
                            markTop = setOf(),
                            markDefiniteBounds = setOf(),
                            loopBounds = treapMapOf()
                        )
                )
            }
        }

        val depMap = mutableMapOf<AllocationAnalysis.AbstractLocation, Set<AllocationAnalysis.AbstractLocation>>()
        logger.debug { "Starting out worklist iteration" }
        if(logger.isDebugEnabled) {
            logger.debug { "INITIAL" }
            toSubmit.forEach {
                logger.debug { it.toString() }
            }
        }
        (object : MonadicStatefulParallelWorklistIteration<
                Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>,
                (MutableCollection<Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>>) -> Unit, Unit, Unit>(
            inheritPool = (Thread.currentThread() as? ParallelPool.ParallelPoolWorkerThread)?.parallelPool
        ) {
            override fun reduce(results: List<Unit>) { return }

            override fun process(
                    it: Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>
            ): ParallelStepResult<(MutableCollection<Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>>) -> Unit, Unit, Unit> {
                val (loc, susp) = it
                val remaining = susp.dep - toRet.keys
                if(remaining.isNotEmpty()) {
                    logger.info {
                        "Scheduled analysis of $loc, but it still depends on the results of ${susp.dep} (yet to complete: $remaining)"
                    }
                    return if(remaining.any {
                                it in failLocs || it in deadAllocations
                            }) {
                        this.cont { _ ->
                            failLocs.add(it.first)
                        }
                    } else {
                        logger.info {
                            "Suspending iteration of $loc"
                        }

                        this.cont { l ->
                            l.add(it)
                        }
                    }
                }
                val res = doInnerAnalysis(loc, susp, toRet, pool) ?: run {
                    logger.warn {
                        "Analysis of suspended analysis for $loc failed"
                    }
                    logger.debug {
                        "Initial state: $susp"
                    }
                    return this.cont { _ ->
                        failLocs.add(it.first)
                    }
                }
                return if(res is AnalysisResult.Suspend) {
                    val newDep = res.dep - depMap.getOrDefault(loc, setOf())
                    for (d in newDep) {
                        if (isCircularDependency(loc, d, depMap)) {
                            logger.warn {
                                "Found a circular dependency between $loc and $newDep. Giving up"
                            }
                            logger.debug {
                                "Map: $depMap"
                            }
                            return this.cont { _ ->
                                failLocs.add(it.first)
                            }
                        }
                    }
                    this.cont { l ->
                        depMap.merge(loc, newDep) { a, b -> a.union(b) }
                        l.add(loc to res)
                    }
                } else if (res is AnalysisResult.Ignored) {
                    this.cont {
                        deadAllocations.add(loc)
                    }
                } else {
                    check(res is AnalysisResult.Complete)
                    this.cont {
                        toRet[loc] = res
                    }
                }
            }

            override fun commit(
                    c: (MutableCollection<Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>>) -> Unit,
                    nxt: MutableCollection<Pair<AllocationAnalysis.AbstractLocation, AnalysisResult.Suspend>>,
                    res: MutableCollection<Unit>
            ) {
                c(nxt)
            }
        }).submit(toSubmit)
        if(failLocs.isNotEmpty()) {
            logger.warn { "The outer analysis failed for the following abstract locations (${failLocs.take(10)}...)" }
        }
        failLocs.flatMapTo(failures) {
            sites[it].orEmpty()
        }
        val withDependencies = toRet.mapValues {
            it.value.copy(
                nested = depMap[it.key].orEmpty()
            )
        }
        val inferredBounds = toRet.flatMap { (_, comp) ->
            comp.loopBounds.entries
        }.groupBy({ it.key }, { it.value }).mapValues { (l, bounds) ->
            bounds.uniqueOrNull() ?: error("Conflicting bounds for loop $l: $bounds, this can't be right")
        }
        return InitLoopResult(
            closePoints = withDependencies,
            fourByteWrite = fourByteWrites,
            failurePoints = failures,
            markLengthReads = withDependencies.flatMapTo(mutableSetOf()) {
                it.value.markTop
            },
            deadAllocations = deadAllocations,
            markDefiniteBounds = withDependencies.flatMapTo(mutableSetOf()) {
                it.value.markDefiniteBounds
            },
            inferredLoopBounds = inferredBounds
        )
    }

    private val storageArrayLengthFinder by lazy {
        StorageArrayLengthFinder(graph)
    }

    private val zeroIdxMatcher by lazy {
        PatternMatcher.compilePattern(graph,PatternDSL.build {
            (Var `==` 0x0()).locFirst
        })
    }

    private fun checkStorageArrayCopy(dynBlock: AllocationAnalysis.Alloc.DynamicBlock): NBId? {
        if(dynBlock.eSz != 32.toBigInteger()) {
            return null
        }
        val def = nonTrivialDefAnalysis.nontrivialDefSingleOrNull(dynBlock.lengthSym.second, dynBlock.lengthSym.first)
            ?: return null
        // preceding read in this block
        if (def.block != dynBlock.lengthSym.first.block || def.pos >= dynBlock.lengthSym.first.pos) {
            return null
        }
        val lc = graph.elab(def)
        if (lc.cmd !is TACCmd.Simple.AssigningCmd.WordLoad) {
            return null
        }
        // now check this block's edge command
        val block = graph.elab(def.block)
        val lst = block.commands.last()
        if(lst.cmd !is TACCmd.Simple.JumpiCmd) {
            return null
        }
        if(lst.cmd.cond !is TACSymbol.Var) {
            return null
        }
        val lenCheck = zeroIdxMatcher.query(lst.cmd.cond, lst).toNullableResult() ?: return null
        val d = nonTrivialDefAnalysis.nontrivialDefSingleOrNull(lenCheck.second, lenCheck.first)
        if(d == null) {
            return null
        }
        val singleton = graph.elab(d)
        if(singleton.cmd !is TACCmd.Simple.AssigningCmd.WordLoad) {
            return null
        }
        if(!storageArrayLengthFinder.isStorageArrayLengthRead(singleton.narrow(), dynBlock)) {
            return null
        }
        return lst.cmd.dst
    }

    private val defAnalysis by lazy {
        graph.cache.def
    }

    private fun isCircularDependency(
            loc: AllocationAnalysis.AbstractLocation,
            curr: AllocationAnalysis.AbstractLocation,
            depMap: Map<AllocationAnalysis.AbstractLocation, Set<AllocationAnalysis.AbstractLocation>>): Boolean {
        if(curr !in depMap) {
            return false
        }
        if(loc in depMap[curr]!!) {
            return true
        }
        return depMap[curr]!!.any {
            isCircularDependency(loc, it, depMap)
        }
    }

    private val Loop.succ : NBId? get() {
        return this.body.flatMap {
            graph.succ(it).filter {
                it !in this.body && it !in revertBlocks
            }
        }.singleOrNull()
    }

    private val Loop.exitEdge : Pair<CmdPointer, CmdPointer>? get() {
        return this.body.flatMap {
            graph.succ(it).filter {
                it !in this.body && it !in revertBlocks
            }
        }.singleOrNull()?.let {
            graph.elab(it).commands.first().ptr
        }?.let { succPtr ->
            graph.pred(succPtr).singleOrNull { predPtr ->
                predPtr.block in this.body
            }?.let { uniqPred ->
                uniqPred to succPtr
            }
        }
    }

    private val blockScheduler = graph.cache.naturalBlockScheduler
    private val visited = newKeySet<NBId>()

    private val roundUpAt = PatternDSL.build {
        roundUp {
            Var { v, where ->
                PatternMatcher.VariableMatch.Match(v to where.ptr)
            }
        }
    }.let {
        PatternMatcher.compilePattern(graph, it)
    }

    private fun doInnerAnalysis(
        thisLoc: AllocationAnalysis.AbstractLocation,
        start: AnalysisResult.Suspend,
        endPoints: Map<AllocationAnalysis.AbstractLocation, AnalysisResult.Complete>,
        pool: ParallelPool
    ): AnalysisResult? {
        val allocSites = this.allocSites.abstractAllocations
        logger.debug { "Beginning analysis of $thisLoc" }
        logger.debug { "Currently cached results ${start.ir}" }
        logger.debug { "Start points: ${start.remaining}" }
        val nested = mutableSetOf<AllocationAnalysis.AbstractLocation>()
        val states = start.state.toMutableMap()
        val mutated = start.mutated.toMutableSet()
        val markTop = start.markTop.toMutableSet()
        val markDefiniteBounds = start.markDefiniteBounds.toMutableSet()
        val loopBounds = start.loopBounds.builder()
        return (object : StatefulWorklistIteration<CmdPointer, AnalysisIR, AnalysisResult?>() {
            override val scheduler: IWorklistScheduler<CmdPointer> = object : IWorklistScheduler<CmdPointer> {
                private val wrapped = blockScheduler

                override fun shouldSchedule(v: CmdPointer, work: () -> Set<CmdPointer>): Boolean {
                    val blockOf = v.block
                    val w = work()
                    return wrapped.shouldSchedule(blockOf, { w.mapToTreapSet { it.block }}) && w.none {
                        it.block == v.block && it.pos < v.pos
                    }
                }
            }

            override fun reduce(results: List<AnalysisIR>): AnalysisResult? {
                var hasRevertPath = false
                if(nested.isEmpty()) {
                    val close = mutableSetOf<Pair<CmdPointer, CmdPointer?>>()
                    val zeroWritePoint = mutableSetOf<CmdPointer>()
                    val inducedClose = mutableSetOf<CmdPointer>()
                    for (r in (results + start.ir)) {
                        when (r) {
                            is AnalysisIR.InitClose -> if(r.pathInducedClose) {
                                // this makes no sense?
                                /*
                                  XXX(jtoman): it's very possible that the solidity compiler could generate a conditional
                                  copy if the length was non-zero, in which case we would have an induced close with
                                  a zero write. If that happens, this will fail, and this message should help
                                 */
                                if(r.zeroWritePoint != null) {
                                    logger.warn {
                                        "Path induced $r close should *not* have a zero write point, they are incompatible (for $thisLoc)"
                                    }
                                    return null
                                }
                                inducedClose.add(r.v)
                            } else {
                                if(r.zeroWritePoint != null) {
                                    zeroWritePoint.add(r.zeroWritePoint)
                                }
                                close.add(r.v to r.pred)
                            }
                            is AnalysisIR.Reverted -> hasRevertPath = true
                            is AnalysisIR.Suspended -> error("Impossible, invariant violated")
                        }
                    }
                    if(zeroWritePoint.size > 1 || zeroWritePoint.isNotEmpty() && (close.size > 1 || inducedClose.isNotEmpty())) {
                        logger.warn {
                            "Have multiple zero write points $zeroWritePoint or zero writes with multiple close points $close for $thisLoc"
                        }
                        return null
                    }
                    if(close.size > 1 && inducedClose.isEmpty()) {
                        return null
                    }
                    if(inducedClose.size > 1) {
                        return null
                    }
                    if(inducedClose.isNotEmpty()) {
                        if (!checkInducedPostDomination(inducedClose.first(), close.map {
                                    it.first
                                }.toSet())) {
                            return null
                        }
                    }

                    if (inducedClose.isEmpty() && close.isEmpty()) {
                        if(!hasRevertPath) {
                            logger.warn {
                                "Have no close points and we aren't reverting: we didn't find the close for $thisLoc"
                            }
                            return null
                        }
                        return AnalysisResult.Ignored
                    }

                    val closePoint = inducedClose.singleOrNull()?.toBefore(null) ?: (close.singleOrNull()?.let { (curr, pred) ->
                        if(pred == null) {
                            CloseLocation.Before(curr, zeroWritePointer = zeroWritePoint.singleOrNull())
                        } else {
                            CloseLocation.AlongEdge(from = pred, to = curr, zeroWritePointer = zeroWritePoint.singleOrNull())
                        }
                    } ?: error("$inducedClose & $close for $thisLoc"))
                    return AnalysisResult.Complete(close = closePoint, nested = setOf(), mutated = mutated, markTop = markTop, markDefiniteBounds = markDefiniteBounds, loopBounds = loopBounds)
                } else {
                    logger.debug { "In analyzing $thisLoc, encountered new nested dependency on $nested. Suspending" }
                    val newIr : MutableList<AnalysisIR.InitClose> = start.ir.toMutableList()
                    val toResume = mutableSetOf<CmdPointer>()
                    for(r in results) {
                        when(r) {
                            is AnalysisIR.InitClose -> newIr.add(r)
                            is AnalysisIR.Suspended -> toResume.add(r.v)
                            is AnalysisIR.Reverted -> {
                                // intentionally left blank
                            }
                        }
                    }
                    return AnalysisResult.Suspend(
                        ir = newIr,
                        remaining = toResume.toList(),
                        state = states,
                        dep = start.dep + nested,
                        mutated = mutated,
                        markTop = markTop,
                        markDefiniteBounds = markDefiniteBounds,
                        loopBounds = loopBounds.build()
                    )
                }
            }

            private fun handleLoop(
                it: CmdPointer,
                state : State
            ) : StepResult<CmdPointer, AnalysisIR, AnalysisResult?> {
                val l = loopHeaders[it.block]!!
                val nestedAllocs = mutableSetOf<AllocationAnalysis.AbstractLocation>().let { tgt ->
                    l.body.forEach {
                        graph.elab(it).commands.mapNotNullTo(tgt) {
                            allocSites[it.ptr]
                        }
                    }
                    tgt
                }
                if(nestedAllocs.isNotEmpty()) {
                    if(thisLoc in nestedAllocs) {
                        logger.warn { "Found nested alloc for $thisLoc within loop ${it.block}, this is not allowed" }
                        return this.halt(null)
                    }
                    nested.addAll(nestedAllocs)
                    if(nestedAllocs.any {
                            it !in endPoints
                        }) {
                        return this.result(AnalysisIR.Suspended(it))
                    }
                }
                val summ = summarizeLoop(l) ?: return run {
                    logger.warn { "Failed to analyze/summarize loop $l during analysis of $thisLoc" }
                    this.halt(null)
                }
                fun skipLoop() : StepResult<CmdPointer, AnalysisIR, AnalysisResult?> {
                    val skipState = havocMutSet(state, mutated, summ.iterationEffect.filter {
                        !it.value.equivSym(it.key)
                    }.keys)
                    val succ = l.succ ?: return run {
                        logger.warn {
                            "Failed to find single successor for loop $l, failing analysis of $thisLoc"
                        }
                        this.halt(null)
                    }
                    return propagateSuccessor(listOf(CmdPointer(block = succ, pos = 0) to skipState)).let {
                        this.cont(it)
                    }
                }
                // skip the loop, assuming this is sufficient
                if(summ.getAllWrites().all {
                        isScratchPointerWrite(it, summ, setOf(TACKeyword.MEM64.toVar()) + state.mustScratch)
                    }) {
                    return skipLoop()
                }
                val writeSummarizer = InitializationLoopSummarization(state, thisLoc, blaster)
                val writeEvery = pool.run(writeSummarizer.isArrayWriteStride(summ)).singleOrNull()

                if(writeEvery == null) {
                    logger.info {
                        "This loop $l writes memory but it is not an array write stride. marking writes with bounded proof obligation"
                    }
                    logger.info {
                        "Loop summary is: $summ"
                    }
                    l.body.flatMapTo(markDefiniteBounds) {
                        graph.elab(it).commands.filter {
                            it.cmd is TACCmd.Simple.DirectMemoryAccessCmd && it.cmd.base == TACKeyword.MEMORY.toVar() &&
                                it.cmd.loc is TACSymbol.Var
                        }.map {
                            it.ptr
                        }
                    }
                    return skipLoop()
                }
                // if this is a constant array allocation loop, record that information as a hint to the unroller later
                if(thisLoc.sort is AllocationAnalysis.InterpAsConstantArray) {
                    val sz = thisLoc.sort.interpAsConstantArraySize()
                    val eSize = when(thisLoc.sort) {
                        is AllocationAnalysis.Alloc.ConstBlock -> EVM_WORD_SIZE
                        is AllocationAnalysis.Alloc.ConstantArrayAlloc -> thisLoc.sort.eSz
                        is AllocationAnalysis.Alloc.ConstantStringAlloc -> BigInteger.ONE
                    }
                    val numBytes = eSize * sz
                    val numIterations = numBytes.divRoundUp(writeEvery.strideBy)
                    loopBounds[l.head] = numIterations
                }
                if(state.elemSize == null) {
                    if(thisLoc.sort !is AllocationAnalysis.Alloc.ConstBlock) {
                        logger.warn {
                            "How can we have a null element size for dynamic type $thisLoc? Conservatively halting"
                        }
                        return this.halt(null)
                    }
                    if(writeEvery.assumedSize != EVM_WORD_SIZE || writeEvery.strideBy != EVM_WORD_SIZE) {
                        logger.warn {
                            "Loop analysis succeeded, but initialization stride was not word aligned: $writeEvery"
                        }
                        return this.halt(null)
                    }
                    if(writeEvery.roles.entries.any { (v, r) ->
                            r == AbstractArraySummaryExtractor.LoopRole.LENGTH && state.num[v]?.x?.takeIf {
                                it.isConstant
                            }?.c != thisLoc.sort.interpAsConstantArraySize()
                        }) {
                        logger.warn {
                            "Loop analysis assumed that length var ${writeEvery.roles} was not equal to length of block: ${thisLoc.sort.interpAsConstantArraySize()}"
                        }
                        return this.halt(null)
                    }
                    val s = l.exitEdge ?: return run {
                        logger.warn {
                            "Failed to find unique successor of loop $l. Failing analysis of $thisLoc"
                        }
                        this.halt(null)
                    }
                    havocMutSet(state, mutated, summ.iterationEffect.keys)
                    return this.result(AnalysisIR.InitClose(v = s.second, pathInducedClose = false, pred = s.first, zeroWritePoint = null))
                }
                if(writeEvery.assumedSize != state.elemSize) {
                    logger.warn {
                        "Assumed size in loop summary ${writeEvery.assumedSize} != ${state.elemSize}. Failing analysis of $thisLoc"
                    }
                    logger.debug {
                        "Loop $l with summary $summ and write every $writeEvery"
                    }
                    return this.halt(null)
                }
                val d = pool.run(loopFixup.isPostWriteFixup(l, loopSummarization = summ, w = writeEvery, g = graph))
                val stateWithMut = havocMutSet(state, mutated, summ.iterationEffect.keysMatching { v, eff ->
                    !eff.equivSym(v)
                })
                val (pred, continuePoint) = if(d == null) {
                    val (fromEdge, toEdge) = l.exitEdge ?: run {
                        logger.warn { "No exit edge from loop header $l" }
                        return this.halt(null)
                    }
                    fromEdge to toEdge
                } else {
                    null to when (d) {
                        is CommonFixupReasoning.PostWriteFixup.ConditionalFixup -> graph.elab(d.succBlock).commands.first().ptr
                        is CommonFixupReasoning.PostWriteFixup.SplitFixup -> graph.succ(d.finalWrite).first()
                    }
                }
                if(state.seenLengthWrite != true) {
                    logger.info {
                        "Loop finished, but length not yet written, updating bounds and continuing"
                    }
                    val (updateScale, updateVar) = (state.inv matches {
                        WRITE + k("scale") * v("len") {
                            it is LVar.PVar
                        } `=` END_BLOCK
                    }).firstNotNullOfOrNull {
                        it.factors["scale"]!! to (it.symbols["len"] as LVar.PVar)
                    } ?: return run {
                        logger.warn {
                            "Could not find term to update bounds at loop $l, failing"
                        }
                        this.halt(null)
                    }
                    val withUpdatedInv = stateWithMut.copy(
                        inv = stateWithMut.inv.updateElements upd@{ eq ->
                            eq.updateSynthetic(WRITE, updateVar, updateScale)
                        }.canonicalize()
                    )
                    return propagateSuccessor(listOf(continuePoint to withUpdatedInv)).let {
                        this.cont(it)
                    }
                }
                // okay great, is this a post fixup?
                return this.result(
                    AnalysisIR.InitClose(
                        pred = pred,
                        v = continuePoint,
                        zeroWritePoint = null,
                        pathInducedClose = false
                    )
                )

            }

            override fun process(it: CmdPointer): StepResult<CmdPointer, AnalysisIR, AnalysisResult?> {
                visited.add(it.block)
                if(it.block in revertBlocks) {
                    return this.result(listOf(AnalysisIR.Reverted(it)))
                }
                if(it !in states) {
                    logger.debug { "Asked to analyze from $it, but it wasn't there. Likely removed by loop interpolation" }
                    return this.result(listOf())
                }
                val state = states[it]!!
                // BEGIN LOOP HANDLING
                if(it.pos == 0 && it.block in loopHeaders) {
                    return handleLoop(it, state)
                }
                val entry = states[it]!!.let { st ->
                    if(it.pos == 0) {
                        val eqs = mutableListOf<Pair<TACSymbol.Var, TACSymbol.Var>>()
                        for((k, v) in st.num) {
                            for(q in v.qual) {
                                if(q is Relational.EqVar) {
                                    eqs.add(k to q.x)
                                }
                            }
                        }
                        if(eqs.isNotEmpty()) {
                            st.copy(
                                inv = st.inv.propagateEquality(eqs) {
                                    st.num[it]?.let { v ->
                                        if(v.x.isConstant) {
                                            v.x.c
                                        } else {
                                            null
                                        }
                                    }
                                }
                            )
                        } else {
                            st
                        }
                    } else {
                        st
                    }
                }
                if(nested.isNotEmpty()) {
                    logger.debug { "We must suspend here" }
                    return this.result(AnalysisIR.Suspended(it))
                }

                if(it in allocSites && allocSites[it]!! != thisLoc) {
                    return if(allocSites[it]!! !in endPoints) {
                        logger.info {
                            "Found new nested allocation site $it, for allocation ${allocSites[it]} (current alloc $thisLoc at sites ${allocSites.filterValues { it == thisLoc }.keys})"
                        }
                        nested.add(allocSites[it]!!)
                        this.result(AnalysisIR.Suspended(it))
                    } else {
                        val completed = endPoints[allocSites[it]!!]!!
                        val mutSet = completed.mutated
                        val completedSummaryState = havocMutSet(entry, mutated, mutSet)
                        return propagateSuccessor(listOf(
                                completed.close.where to completedSummaryState
                        )).let { this.cont(it) }
                    }
                } else if(it in allocSites && allocSites[it] == thisLoc && entry.seenAllocClose != false) {
                    logger.warn {
                        "Found what appears to be a circular alloc, this is very much disallowed"
                    }
                    return this.halt(null)
                }

                /**
                 * Check if we've somehow lost track of our instrumentation points, if so, we need to halt
                 */
                if(entry.inv.none {
                            it.relates(WRITE) && it.relates(END_BLOCK)
                        }) {
                    logger.warn {
                        "Lost track of write and end block pointers at $it for location $thisLoc, reads at ${allocSites.entries.filter { it.value == thisLoc }.map { it.key }.toSet()}"
                    }
                    logger.debug {
                        "Current invariants: ${entry.inv}"
                    }
                    return this.halt(null)
                }
                val lc = graph.elab(it)
                if(!checkWriteSafety(lc, entry)) {
                    logger.info {
                        "Could not prove write safe for $lc during analysis of $thisLoc"
                    }
                    logger.debug { "must scratch: ${entry.mustScratch} & current inv: ${entry.inv}" }
                    markDefiniteBounds.add(it)
                }
                val (stepped, markRead) = stepCommand(entry, lc)
                if(markRead) {
                    markTop.add(it)
                }

                val unreachable = mutableMapOf<CmdPointer, PathCondition>()

                val completeResults = mutableListOf<AnalysisIR.InitClose>()

                val next = graph.pathConditionsOf(it).mapNotNull { (succ, path) ->
                    unreachable[succ] = path
                    numericInterpreter.propagate(
                            pathCondition = path, l = graph.elab(succ), w = stepped
                    )?.let { prop ->
                        unreachable.remove(succ)
                        val sat = stepped.inv.propagateEqualities(listOf(), listOf()) { it ->
                            prop[it]?.let {
                                if(it.x.isConstant) {
                                    it.x.c
                                } else {
                                    null
                                }
                            }
                        }
                        /**
                         * See if `WRITE = END_BLOCK` (with some slippage). We actually check whether WRITE = END_BLOCK + k
                         * where k is bounded to be in the range [0,31] (inclusive). We don't require an *exact* match because
                         * the distance between WRITE and END_BLOCK might not be word aligned, for example, if we have
                         * a constant array of size 4 whose elements are 1 byte.
                         *
                         * then WRITE + 4 = END_BLOCk, but if the array is initialized by writing a single word at
                         * the start of the array data segment, then we'll advance WRITE "past" END_BLOCK, but will have
                         * definitely initialized the whole array. (This is the scenario that happened with https://certora.atlassian.net/browse/CERT-5158)
                         */
                        val matchWithSlippage = sat matchesAny {
                            WRITE `=` END_BLOCK + k("const") {
                                BigInteger.ZERO <= it && it < EVM_WORD_SIZE
                            }
                        } != null

                        /**
                         * We have then that:
                         * WRITE + len `=` END_BLOCK + k
                         * where `len < k`, so k - len > 0
                         * from which we have:
                         * WRITE - END_BLOCK = k - len > 0
                         * aka WRITE - END_BLOCK > 0
                         * aka the initialization is complete
                         *
                         * The names of "len" and the insistence of the length role is because this "pattern"
                         * appears in when copying packed strings out of storage.
                         */
                        val matchWithBound = (sat matches {
                            WRITE + v("len") {
                                it is LVar.PVar && state.num[it.v]?.qual.orEmpty().contains(Roles.LENGTH)
                            } `=` END_BLOCK + k("write")
                        }).any { m ->
                            (m.symbols["len"] as? LVar.PVar)?.let { state.num[it.v] }?.let { lVar ->
                                lVar.x.ub < m.factors["write"]
                            } == true
                        }

                        fun roundedUpLength(v: TACSymbol.Var) = roundUpAt.query(v, graph.elab(it)).toNullableResult()?.let { (v, where) ->
                            /**
                             * If so, is the variable we're rouding up equal to the length of the array?
                             */
                            graph.cache.gvn.findCopiesAt(it, where to v).any { lenAlias ->
                                state.num[lenAlias]?.qual.orEmpty().contains(Roles.LENGTH)
                            }
                        } == true

                        /**
                         * Check whether
                         * WRITE + len - V = END
                         * where V is the length rounded up. The initialization is complete.
                         */
                        val matchWithRounding = (sat matchesAny {
                            WRITE + v("len") {
                                it is LVar.PVar && state.num[it.v]?.qual.orEmpty().contains(Roles.LENGTH)
                            } - v("over-write") { ow ->
                                /**
                                 * Is this a round up? Either check whether ow.v is itself a rounded up length (the first
                                 * disjunct) OR there is some variable y st ow.v = y and where y is a rounded up length.
                                 */
                                ow is LVar.PVar && (roundedUpLength(ow.v) || sat.containsAny { saturatingEq ->
                                    if(!saturatingEq.relates(ow.v) || saturatingEq.k != BigInteger.ZERO || saturatingEq.term.size != 2) {
                                        return@containsAny false
                                    }
                                    val scale = saturatingEq.term[ow]!!
                                    if(scale.abs() != BigInteger.ONE) {
                                        return@containsAny false
                                    }
                                    val other = saturatingEq.term.entries.singleOrNull {
                                        it.key != ow && it.key is LVar.PVar
                                    } ?: return@containsAny false
                                    if(other.value != scale.negate()) {
                                        return@containsAny false
                                    }
                                    /**
                                     * We thus have that [saturatingEq] represents k1 * ow.v + k2 * other = 0,
                                     * where |k1| = 1 and k1 = -k2. We thus have that ow.v = other, and thus if other is
                                     * a rounded up length, then ow.v is too.
                                     */
                                    roundedUpLength((other.key as LVar.PVar).v)
                                })
                            } `=` END_BLOCK
                        }) != null
                        if((matchWithSlippage || matchWithBound || matchWithRounding) && (stepped.elemSize == null || stepped.seenLengthWrite == true)) {
                            logger.debug { "Have that the written bytes have reached the end of the block. completing $thisLoc" }
                            /*
                                special check to see if we do ye olde extra write 0 trick
                             */
                            val specialClose = run {
                                val loc = graph.iterateBlock(it).firstMapped {
                                    if(it.cmd is TACCmd.Simple.AssigningCmd.ByteStore && it.cmd.value is TACSymbol.Const && it.cmd.value.value == BigInteger.ZERO
                                            && it.cmd.base == TACKeyword.MEMORY.toVar() && it.cmd.loc is TACSymbol.Var) {
                                        it.narrow<TACCmd.Simple.AssigningCmd.ByteStore>()
                                    } else {
                                        null
                                    }
                                }
                                loc?.let { view ->
                                    DefiningEquationAnalysis.getDefiningEquation(g = graph, v = view.cmd.loc as TACSymbol.Var, where = view.ptr, target = it)
                                }?.let { eq ->
                                    LinearTerm.lift(eq)
                                }?.let { term ->
                                    if(entry.inv.implies {
                                                term `=` END_BLOCK
                                            } && loc.ptr.pos != graph.elab(loc.ptr.block).commands.lastIndex) {
                                        AnalysisIR.InitClose(v = loc.ptr.copy(pos = loc.ptr.pos + 1), pred = loc.ptr, pathInducedClose = false, zeroWritePoint = it)
                                    } else {
                                        null
                                    }
                                }
                            }
                            val result = specialClose ?: AnalysisIR.InitClose(
                                    v = succ, pred = it, pathInducedClose = path != PathCondition.TRUE, zeroWritePoint = null
                            )
                            completeResults.add(result)
                            null
                        } else {
                            succ to stepped.copy(
                                    num = prop,
                                    inv = sat
                            )
                        }
                    }
                }

                /* SPECIAL CASE:

                   Is one of the successors unreachable? If so, is this due to an impossible condition on the (constant) length?

                   Inquiring minds want to know!
                 */
                if(unreachable.isNotEmpty() && unreachable.size == 1 && state.constSize != null) {
                    val (where, cond) = unreachable.entries.first()
                    val condVar = when(cond) {
                        is PathCondition.NonZero -> {
                            cond.v
                        }
                        is PathCondition.EqZero -> {
                            cond.v
                        }
                        else -> null
                    }
                    if(condVar != null) {
                        // XXX(jtoman): rather special cased here
                        fun TACSymbol.interpToConst() = when(this) {
                            is TACSymbol.Const -> this.value
                            is TACSymbol.Var -> stepped.num[this]?.let {
                                if(it.x.isConstant) {
                                    it.x.c
                                } else {
                                    null
                                }
                            }
                        }
                        val impossibleLenCond = stepped.num[condVar]?.qual?.filterIsInstance(IntQualifier.ConditionVar::class.java)?.filter { it.condition == ConditionQualifier.Condition.EQ}?.map {
                            setOf(it.op2.interpToConst(), it.op1.interpToConst())
                        }?.any {
                            BigInteger.ZERO in it && it.any {
                                it != null && it == stepped.constSize
                            }
                        } == true
                        if(impossibleLenCond) {
                            completeResults.add(AnalysisIR.InitClose(
                                    v = where,  pred = it, pathInducedClose = true, zeroWritePoint = null
                            ))
                        }
                    }
                }

                val nextItems = propagateSuccessor(next)
                /*
                  If we've reached the end of the function *without* only reverting, then
                  something has gone *very* wrong, and we will now conservatively fail the analysis.
                  Failing to do this can send the SIA into an infinite loop if there is a parent object
                  waiting for us to finish, but there's a non-reverting path through the program where we DON'T
                  finish.
                 */
                if(graph.succ(it).isEmpty() && completeResults.isEmpty()) {
                    logger.warn { "Reached end of the function at $it without only reverting, and no complete results" }
                    return this.halt(null)
                }
                return StepResult.Ok(
                        next = nextItems,
                        result = completeResults
                )
            }

            private fun propagateSuccessor(next: List<Pair<CmdPointer, State>>): MutableList<CmdPointer> {
                val nextItems = mutableListOf<CmdPointer>()
                for ((succ, toJoin) in next) {
                    if (succ !in states) {
                        states[succ] = toJoin
                        nextItems.add(succ)
                        continue
                    }
                    val existingState = states[succ]!!
                    val ub = existingState.join(toJoin)
                    if (ub != existingState) {
                        states[succ] = ub
                        nextItems.add(succ)
                    }
                }
                return nextItems
            }

            private fun checkWriteSafety(lc: LTACCmd, st: State): Boolean {
                val cmd = lc.cmd
                val safeBase = when(cmd) {
                    is TACCmd.Simple.WithLongCopy -> {
                        if(cmd.copy.dest.base != TACKeyword.MEMORY.toVar()) {
                            return true
                        }
                        val targetOffs = cmd.copy.dest.offset
                        targetOffs !is TACSymbol.Var ||
                            targetOffs in st.mustScratch || st.inv implies { !targetOffs `=` WRITE }
                    }
                    is TACCmd.Simple.AssigningCmd.ByteStore -> {
                        if(cmd.base != TACKeyword.MEMORY.toVar()) {
                            return true
                        }
                        cmd.loc !is TACSymbol.Var ||
                            cmd.loc in st.mustScratch || st.inv implies { !cmd.loc `=` WRITE } ||
                            isLengthWrite(lc.narrow(), st) || (st.inv matches underWritePattern(cmd.loc)).isNotEmpty()
                    }
                    else -> return true
                }
                if(!safeBase) {
                    logger.debug {
                        "Could not prove that the base pointer of $cmd pointed to write"
                    }
                    return false
                }
                /*
                   See if it is safe to be writing into the intialization buffer.
                   First case, the buffer isn't done yet
                 */
                return st.inv.any { linearEquality ->
                    linearEquality.checkWriteEndSpacing(st)
                } ||
                /*
                 * an exception to the above: we always treat a write of a length as safe. When would we be writing the length
                 * where the above WRITE <= END_BLOCK not hold? When we are initializing a non-word aligned constant
                 * array via byte stores. As documented elsewhere, this can cause WRITE to point "slightly" (read: less than one word)
                 * past the end of END_BLOCK.
                 */
                (lc.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.let {
                    isLengthWrite(it, st)
                } == true) ||
                (lc.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.let {
                    isLastPackedWrite(it, st)
                } == true)
            }

            /**
             * This can happen if we are packing several sub-word values into the last word of a constant sized array.
             *
             * For example, suppose we have:
             * uint8 a;
             * uint16 b;
             * uint c;
             * and
             * bytes memory k = abi.encodePacked(c, b, a);
             * this array has constant size 35 so WRITE + 35 = END_BLOCK
             * after the write of c, we have
             * WRITE + 3 = END_BLOCk, and then after the write of b (which looks like a write of a full-word), we have:
             * WRITE - 29 = END_BLOCK.
             * At this point, the initialization is "done" but logically we only wrote 2 bytes for b, we still have to
             * write the single byte of a.
             *
             * Generalizing this problem, we had a write at x, where we have the following series of equations:
             * x + k = END
             * x = old(WRITE)
             * 0 < k < 32
             * WRITE = old(WRITE) + 32
             *
             * From which we can conclude that WRITE > END. We now have a write to location y, and want to show that it falls between
             * x and END, i.e., it was some packed write suffix. To do that, it is sufficient to show that
             * there exists w such that:
             * WRITE = END + offs, that y = WRITE + w, and that -32 < w < offs < 0. We query the invariants
             * to find this w, and return true if we can find it (which shows the write to `y` is of the acceptable form).
             *
             * The justification for this is as follows:
             * From WRITE = old(WRITE) + 32, we have
             * WRITE - 32 + k = END.
             * Call the term `-32 + k` "offs". Now let `y = WRITE + w` for some `-32 < w < offs`. From `-32 < w`
             * we must have that `w = -32 + j` for some `j > 0`. Via substitutions we have that `-32 + j < -32 + k`,
             * aka `j < k`. Then, `y = (old(WRITE) + 32) - 32 + j, AKA `y = old(WRITE) + j` AKA `y = x + j < END`.
             */
            private fun isLastPackedWrite(
                lc: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>,
                st: State
            ) : Boolean {
                if((thisLoc.sort as? AllocationAnalysis.WithElementSize)?.getElementSize() != BigInteger.ONE) {
                    return false
                }
                val offs = (st.inv matchesAny  {
                    END_BLOCK `=` WRITE + k("offs") {
                        it < BigInteger.ZERO
                    }
                })?.factors?.get("offs") ?: return false
                val loc = lc.cmd.loc as? TACSymbol.Var ?: return false
                return st.inv matchesAny {
                    loc `=` WRITE + k("dummy") { w ->
                        EVM_WORD_SIZE.negate() < w && w < offs
                    }
                } != null
            }

            /**
             * Does this linear invariant satisfy the following conditions?
             * 1) It relates END_BLOCK, WRITE, and zero or more program variables
             * 2) END_BLOCK and WRITE have different signedness
             * 3) the absolute value of END_BLOCK and WRITE's coefficients are one
             *
             * If so, then we
             * WRITE + v1 * r1 + v2 * r2 + ... k = END_BLOCK. If v1 * r1 + ... k >= 0, then we
             * must have WRITE <= END_BLOCK. To check this condition, we find the minimal value of v1 * r1 + ... k.
             * Using the numeric interval information in [st] we substitute the maximum values for vi when ri < 0, and the
             * minimum value of vi when ri >= 0. Summing the result gives the lower bound on the term, and if this lower bound
             * is >= 0, we have our result.
             *
             * In other words, are there bytes left to write before initialization completes?
             *
             */
            private fun LinearEquality.checkWriteEndSpacing(
                st: State
            ) : Boolean {
                val writeCoeff = term[WRITE] ?: return false
                val endBlockCoeff = term[END_BLOCK] ?: return false
                if(writeCoeff.abs() != BigInteger.ONE ||
                    endBlockCoeff.abs() != BigInteger.ONE ||
                    writeCoeff < BigInteger.ZERO == endBlockCoeff < BigInteger.ZERO) {
                    return false
                }
                /*
                  so we have either:
                  END_BLOCK - WRITE + e = 0
                  or
                  WRITE - END_BLOCK + e = 0

                  In both cases we want to extract the valuation of e.
                  In the former case, we can rewrite with
                  END_BLOCK = WRITE - e
                  and in the latter case:
                  WRITE + e = END_BLOCK

                  Note that the sign applied to `e` is the original sign of WRITE. So this needs to applied to all
                  coefficients and the constant terms in e.
                  For example, if we have:
                  END_BLOCK - WRITE - 5 * x = 0
                  `e` here is `-5 * x`
                  so applying the normalization term we have:
                  `5*x` which is what we'd get with our algebraic manipulation
                 */
                @Suppress("UnnecessaryVariable") val normalizationTerm = writeCoeff
                var accum = k * normalizationTerm
                for((v, rawCoeff) in term) {
                    if(v !is LVar.PVar) {
                        continue
                    }
                    val coeff = normalizationTerm * rawCoeff
                    val abstractVal = st.num.getOrDefault(v.v, IQ.TOP).x
                    accum += coeff * if(coeff < BigInteger.ZERO) {
                        abstractVal.ub
                    } else {
                        abstractVal.lb
                    }
                }
                return accum >= BigInteger.ZERO
            }


        }).submit(start.remaining)
    }

    private class InitializationLoopSummarization(
        private val state: State,
        private val thisLoc: AllocationAnalysis.AbstractLocation,
        blaster: IBlaster
    ) : ArrayLoopSummarization(blaster) {
        override fun isConstant(x: TACSymbol.Var): BigInteger? {
            return state.num[x]?.x?.takeIf { it.isConstant }?.c
        }

        private val saturatedInv = state.inv.getEqualities().let { equalities ->
            val lengthEqualities = state.inv.matches {
                v("scaled") {
                    it is LVar.PVar
                } `=` v("lenVar") {
                    it is LVar.PVar && state.num[it.v]?.qual?.contains(Roles.LENGTH) == true
                } * n("factor")
            }.map {
                (it.symbols.get("scaled") as LVar.PVar).v to (
                    (it.symbols.get("lenVar") as LVar.PVar).v to it.factors.get("factor")!!
                    )
            }
            state.inv.propagateEqualities(lengthEqualities, equalities) { _ -> null }
        }

        override fun couldBeDataSegmentSize(l: Loop, cand: TACSymbol.Var): Boolean {
            return saturatedInv implies {
                !cand + WRITE `=` END_BLOCK
            } && (state.num[cand]?.qual?.none {
                it == Roles.LENGTH
            } ?: true) || (saturatedInv matches {
                cand `=` v("len_var") * k("scale")
            }).any {
                saturatedInv implies {
                    it.symbols["len_var"]!! * it.factors["scale"]!! + WRITE `=` END_BLOCK
                }
            }
        }

        override fun plausibleAssignment(l: Loop, v: TACSymbol.Var, r: LoopRole): Boolean {
            return super.plausibleAssignment(l, v, r) && when(r) {
                LoopRole.END -> saturatedInv.implies {
                    !v `=` END_BLOCK
                }
                /*
                  is this value known to be an array length? OR is it known that adding the constant value
                  of v to a pointer equal to write will yield the end block
                 */
                LoopRole.LENGTH -> state.num[v]?.qual?.contains(Roles.LENGTH) == true || state.num[v]?.let { n ->
                    n.x.isConstant && saturatedInv.flatMap {
                        it.term.keys
                    }.any { v ->
                        v != WRITE && saturatedInv.implies { v `=` WRITE } && saturatedInv.implies {
                            v + (n.x.c * 32.toBigInteger()) `=` END_BLOCK
                        }
                    }
                } == true
                LoopRole.OBJ_POINTER -> state.num[v]?.qual?.contains(Roles.ARRAY_START) == true
                LoopRole.ELEM_START -> state.num[v]?.qual?.contains(Roles.ELEM_START) == true || saturatedInv.implies {
                    !v `=` WRITE
                }
                LoopRole.ZERO -> state.num[v]?.x?.mayEqual(BigInteger.ZERO) == true
                LoopRole.ELEM_LENGTH -> true
                LoopRole.CONSTANT -> true
                LoopRole.CORRELATED_ELEM_START,
                LoopRole.CORRELATED_ELEM_END -> false
            }
        }

        override fun getRelationalScale(
            correlatedStartVar: TACSymbol.Var,
            correlatedEndVar: TACSymbol.Var,
        ): BigInteger? {
            return saturatedInv.matches {
                n("scale") * v("len") {
                    it is LVar.PVar && state.num[it.v]?.qual?.any { it == Roles.LENGTH } == true
                } `=` correlatedEndVar - correlatedStartVar
            }.singleOrNull()?.factors?.get("scale") ?: run {
                saturatedInv.matches {
                    END_BLOCK `=` (correlatedEndVar - correlatedStartVar) + v("start") {
                        it == WRITE || saturatedInv implies {
                            it `=` WRITE
                        }
                    }
                }.firstOrNull()?.let {
                    state.elemSize
                }
            } ?: isConstantSizeArray()?.let { sz ->
                saturatedInv matchesAny {
                    correlatedEndVar - correlatedStartVar `=` n("scale") * sz
                }
            }?.factors?.get("scale")
        }

        override public fun isConstantSizeArray(): BigInteger? {
            return (thisLoc.sort as? AllocationAnalysis.InterpAsConstantArray)?.interpAsConstantArraySize()
        }

        override fun plausibleArraySize(l: Loop, sz: BigInteger): Boolean {
            return sz == state.elemSize || (thisLoc.sort is AllocationAnalysis.Alloc.ConstBlock && sz == 32.toBigInteger())
        }
    }

    private fun CmdPointer.toBefore(zeroPoint: CmdPointer? = null) : CloseLocation = CloseLocation.Before(this, zeroPoint)

    private fun isScratchPointerWrite(it: LoopSummarization.MemoryMutation, summ: LoopSummarization.LoopIterationSummary, of: Set<TACSymbol.Var>): Boolean {
        return when(it) {
            is LoopSummarization.MemoryMutation.MemoryCopy -> {
                LoopSummarization.isMonotoneTransformerFor(expr = it.index) {
                    it in of
                }
            }
            is LoopSummarization.MemoryMutation.MemoryWrite -> {
                LoopSummarization.isMonotoneTransformerFor(expr = it.index) {
                    it in of
                }
            }
            is LoopSummarization.MemoryMutation.NestedLoop -> {
                it.writes.all {
                    isScratchPointerWrite(it, summ, of)
                }
            }
            is LoopSummarization.MemoryMutation.MemoryMutationOver -> {
                it.v.any { e ->
                    LoopSummarization.isMonotoneTransformerFor(e) { v ->
                        v in of
                    }
                }
            }
        }
    }

    private fun checkInducedPostDomination(inducedClose: CmdPointer, close: Set<CmdPointer>): Boolean {
        val toCheck = mutableSetOf<NBId>()
        for(c in close) {
            if(c.block == inducedClose.block) {
                if(c.pos < inducedClose.pos) {
                    return false
                }
                continue
            }
            toCheck.add(c.block)
        }
        if(toCheck.isEmpty()) {
            return true
        }
        return (object : WorklistIteration<NBId, Unit, Boolean>() {
            override fun process(it: NBId): StepResult<NBId, Unit, Boolean> {
                if(it == inducedClose.block) {
                    return this.result(Unit)
                }
                val succ = graph.succ(it).filter {
                    it !in revertBlocks
                }
                if(succ.size > 1) {
                    return this.halt(false)
                }
                return this.cont(succ)
            }

            override fun reduce(results: List<Unit>): Boolean {
                return true
            }

        }).submit(toCheck)
    }

    private fun havocMutSet(havocState: State, mutated: MutableSet<TACSymbol.Var>, toHavoc: Collection<TACSymbol.Var>): State {
        val numMut = havocState.num.builder()
        mutated.addAll(toHavoc)
        val inv = havocState.inv.mutate { inv ->
            for (v in toHavoc) {
                numMut.remove(v)
                inv.removeIf {
                    it.relates(v)
                }
            }
        }
        return State(
                num = numMut.build(),
                mustScratch = havocState.mustScratch - toHavoc,
                inv = inv,
                seenLengthWrite = havocState.seenLengthWrite,
                elemSize = havocState.elemSize,
                seenAllocClose = havocState.seenAllocClose,
                constSize = havocState.constSize,
                isLengthAssign = havocState.isLengthAssign
        )
    }

    fun analyze() : SimpleInitializationAnalysis.Result? {
        val initOps = outerAnalysisLoop()
        val popResults = mutableMapOf<CmdPointer, MutableSet<AllocationAnalysis.AbstractLocation>>()
        val zeroWriteMarkers = mutableSetOf<CmdPointer>()
        val edgeResults = mutableMapOf<Pair<NBId, NBId>, MutableSet<AllocationAnalysis.AbstractLocation>>()

        for((k,v) in initOps.closePoints) {
            val close = v.close
            if(close.zeroWritePointer != null) {
                zeroWriteMarkers.add(close.zeroWritePointer!!)
            }
            when(close) {
                is CloseLocation.Before -> popResults.computeIfAbsent(close.where) { mutableSetOf() }.add(k)
                is CloseLocation.AlongEdge -> {
                    if(close.from.block == close.to.block) {
                        check(close.from.pos + 1 == close.to.pos)
                        popResults.computeIfAbsent(close.to) { mutableSetOf() }.add(k)
                    } else {
                        check(close.to.pos == 0)
                        edgeResults.computeIfAbsent(close.from.block to close.to.block) { mutableSetOf() }.add(k)
                    }
                }
            }

        }
        if(!simulateStack(allocSites, popResults, edgeResults, initOps.failurePoints, initOps.deadAllocations)) {
            logger.warn { "Failing, as stack verification failed" }
            return null
        }
        return SimpleInitializationAnalysis.Result(
            closePoints = popResults,
            closeEdges = edgeResults,
            zeroWriteMarkers = zeroWriteMarkers,
            fourByteWrite = initOps.fourByteWrite,
            failurePoints = initOps.failurePoints,
            markTopLocs = initOps.markLengthReads,
            markDefiniteBounds = initOps.markDefiniteBounds,
            inferredLoopBounds = initOps.inferredLoopBounds
        )
    }

    private fun simulateStack(
            allocSites: AllocationInformation,
            popPoints: Map<CmdPointer, Set<AllocationAnalysis.AbstractLocation>>,
            edgeResults: Map<Pair<NBId, NBId>, Set<AllocationAnalysis.AbstractLocation>>,
            failurePoints: Set<CmdPointer>,
            deadAllocations: Set<AllocationAnalysis.AbstractLocation>
    ): Boolean {
        return (object : StatefulWorklistIteration<NBId, List<String>, Boolean>() {
            override val scheduler: IWorklistScheduler<NBId> = blockScheduler

            private fun processPop(cmd: Any, stack: MutableList<AllocationAnalysis.AbstractLocation>, d: MutableSet<AllocationAnalysis.AbstractLocation>) : List<String>? {
                if(stack.size < d.size) {
                    return listOf(
                            "While stepping $cmd, in state ${stack}: expected at least ${stack.size} allocations for $d pending pops"
                    )
                }
                if(!stack.containsAll(d)) {
                    return listOf("While stepping $cmd in state ${stack}, did not find all expcted pop allocations: $d")
                }
                repeat(d.size) {
                    if(stack.last() !in d) {
                        return listOf("While stepping $cmd in state ${stack}: unexpected top of stack, remaining: $d")
                    }
                    d.remove(stack.last())
                    stack.removeAt(stack.lastIndex)
                }
                return null
            }

            private val state = mutableMapOf(graph.rootBlocks.first().id to listOf<AllocationAnalysis.AbstractLocation>())
            override fun process(it: NBId): StepResult<NBId, List<String>, Boolean> {
                val st = state[it] ?: error("Missing state of successor!")
                val block = graph.elab(it)
                var stack = st
                for(cmd in block.commands) {
                    if(cmd.ptr in failurePoints) {
                        return this.result(listOf<List<String>>())
                    }
                    if(cmd.ptr in popPoints) {
                        val d = popPoints[cmd.ptr]!!.toMutableSet()
                        val mutStack = stack.toMutableList()
                        stack = processPop(cmd, mutStack, d)?.let {
                            return this.result(it)
                        } ?: mutStack
                    }
                    if(cmd.ptr in allocSites.abstractAllocations) {
                        val alloc = allocSites.abstractAllocations[cmd.ptr]!!
                        if (alloc in deadAllocations) {
                            // ignore dead allocations
                            continue
                        }

                        if(alloc in stack) {
                            if(stack.last() != alloc) {
                                return this.result(
                                        listOf(
                                                "Stepping allocation at $cmd, found self-push for $alloc (in state $stack)"
                                        )
                                )
                            }
                        } else {
                            stack = stack + alloc
                        }
                    }
                }
                val next = mutableListOf<NBId>()
                for(succ in graph.succ(it)) {
                    val edge = it to succ
                    val propStack = if(edge in edgeResults) {
                        val popped = edgeResults[edge]!!.toMutableSet()
                        stack.toMutableList().let { l ->
                            processPop("$it -> $succ", l, popped)?.let {
                                return result(it)
                            } ?: l
                        }
                    } else {
                        stack
                    }
                    // Do not visit [succ] if it reverts, or if the inner analysis
                    // never reached it (and should be considered 'unreachable')
                    if(succ in revertBlocks || succ !in visited) {
                        continue
                    }
                    if(succ in state) {
                        if(state[succ] != propStack) {
                            return this.result(
                                    listOf(
                                            "Transitioning from $it to $succ, inconsistent stacks: $propStack vs ${state[succ]}"
                                    )
                            )
                        }
                    } else {
                        state[succ] = propStack
                        next.add(succ)
                    }
                }
                return this.cont(next)
            }

            override fun reduce(results: List<List<String>>): Boolean {
                if(results.isEmpty()) {
                    return true
                }
                logger.warn {
                    "Could not find stack structure, failing initialization analysis"
                }
                for(msgs in results) {
                    for(d in msgs) {
                        logger.warn { d }
                    }
                }
                return false
            }
        }).submit(graph.rootBlocks.map { it.id })
    }

    enum class LengthReadResult {
        NON_ALIASING_BYTELOAD,
        DIRECT,
        NONE
    }

    private fun getInitialState(
            inv: LinearInvariants,
            eSz: BigInteger?,
            nondetInts: Set<TACSymbol.Var> = emptySet(),
            quals: Map<TACSymbol.Var, SelfQualifier<*>> = emptyMap(),
            constSize: BigInteger?,
            isLengthRead: ((LTACCmdView<TACCmd.Simple.AssigningCmd>) -> LengthReadResult)? = null
    ): State {
        val constantTerms = inv.mapNotNull p@{
            val (k,f) = it.term.singleOrNull() ?: return@p null
            if(k !is LVar.PVar) {
                return@p null
            }
            val v = k.v
            if(v in nondetInts || v in quals) {
                return@p null
            }
            if(f.abs() != BigInteger.ONE) {
                return@p null
            }
            v to IQ(IntValue.Constant(f.negate() * it.k), setOf())
        }

        val num = (nondetInts.map {
            it to IQ(IntValue.Nondet, quals[it]?.let(::setOf).orEmpty())
        } + quals.filter {
            it.key !in nondetInts
        }.map {
            it.key to IQ(IntValue.Nondet, setOf(it.value))
        } + constantTerms).toTreapMap()
        return State(
            inv = inv + if(eSz == null) {
                LinearEquality.build { !TACKeyword.MEM64.toVar() `=` WRITE }
            } else {
                LinearEquality.build { !TACKeyword.MEM64.toVar() + 0x20 `=` WRITE }
            },
            num = num,
            seenLengthWrite = eSz?.let { false },
            elemSize = eSz,
            constSize = constSize,
            mustScratch = setOf(),
            seenAllocClose = false,
            isLengthAssign = isLengthRead
        )
    }

    private sealed class AnalysisIR {
        data class InitClose(override val v: CmdPointer, val pathInducedClose: Boolean, val pred: CmdPointer?, val zeroWritePoint: CmdPointer?) : AnalysisIR()
        data class Suspended(override val v: CmdPointer) : AnalysisIR()
        data class Reverted(override val v: CmdPointer): AnalysisIR()

        abstract val v: CmdPointer
    }

    sealed class CloseLocation {
        abstract val zeroWritePointer: CmdPointer?

        data class Before(val where: CmdPointer, override val zeroWritePointer: CmdPointer?) : CloseLocation()
        data class AlongEdge(val from: CmdPointer, val to: CmdPointer, override val zeroWritePointer: CmdPointer?) : CloseLocation()
    }

    private val CloseLocation.where : CmdPointer get() = when(this) {
        is CloseLocation.Before -> this.where
        is CloseLocation.AlongEdge -> this.to
    }

    /**
     * Allow writing a location that under a word "behind" the current write pointer. During initialization of a constant
     * sized encodePacked buffer we may see writes at logical index `0` (which bumps the synthetic [WRITE] location by 32). But then
     * we may see a write at offset `4`. This is ultimately fine.
     */
    private fun underWritePattern(loc: TACSymbol.Var) = TermMatching.compile {
        WRITE - loc `=` k("diff") {
            it < EVM_WORD_SIZE && it >= BigInteger.ZERO
        }
    }

    private sealed interface SideResults {
        val markTop: Set<CmdPointer>
        val mutated: Set<TACSymbol.Var>
        val markDefiniteBounds: Set<CmdPointer>
    }

    private sealed class AnalysisResult {
        data class Complete(
            val close: CloseLocation,
            val nested: Set<AllocationAnalysis.AbstractLocation>,
            override val mutated: Set<TACSymbol.Var>,
            override val markTop: Set<CmdPointer>,
            override val markDefiniteBounds: Set<CmdPointer>,
            val loopBounds: Map<NBId, BigInteger>
        ) : AnalysisResult(), SideResults

        // For nested allocations
        data class Suspend(
            val ir: List<AnalysisIR.InitClose>,
            val remaining: List<CmdPointer>,
            val state: Map<CmdPointer, State>,
            val dep: Set<AllocationAnalysis.AbstractLocation>,
            override val markTop: Set<CmdPointer>,
            override val mutated: Set<TACSymbol.Var>,
            override val markDefiniteBounds: Set<CmdPointer>,
            val loopBounds: TreapMap<NBId, BigInteger>
        ) : AnalysisResult(), SideResults

        object Ignored : AnalysisResult()
    }

    private data class State(
            val num: TreapMap<TACSymbol.Var, IQ>,
            val mustScratch: Set<TACSymbol.Var>,
            val inv: LinearInvariants,
            val seenLengthWrite: Boolean?,
            val elemSize: BigInteger?,
            val seenAllocClose: Boolean?,
            val constSize: BigInteger?,
            val isLengthAssign: ((LTACCmdView<TACCmd.Simple.AssigningCmd>) -> LengthReadResult)?
    ) {
        fun join(toJoin: State): State {
            return State(
                    num = this.num.join(toJoin.num, IQ(IntValue.Nondet, setOf())),
                    mustScratch = this.mustScratch.intersect(toJoin.mustScratch),
                    inv = this.inv.join(toJoin.inv),
                    seenLengthWrite = this.seenLengthWrite.joinTristateBool(toJoin.seenLengthWrite),
                    elemSize = this.elemSize,
                    seenAllocClose = this.seenAllocClose.joinTristateBool(toJoin.seenAllocClose),
                    constSize = this.constSize,
                    isLengthAssign = this.isLengthAssign.also {
                        check(isLengthAssign === toJoin.isLengthAssign)
                    }
            )
        }

        override fun toString(): String {
            return "State(num=$num, inv=$inv, mustScratch=$mustScratch, seenLengthWrite=$seenLengthWrite, elemSize=$elemSize, visited=[...])"
        }


        private fun Boolean?.joinTristateBool(other: Boolean?) : Boolean? =
                this?.let { a ->
                    other?.let { b ->
                        if(a == b) {
                            a
                        } else {
                            null
                        }
                    }
                }
    }

    private sealed class Roles : SelfQualifier<Roles> {
        override fun relates(v: TACSymbol.Var): Boolean = false
        override fun saturateWith(equivClasses: VariableSaturation): List<Roles> = listOf(this)
        object ARRAY_START : Roles(){ override fun hashCode() = hashObject(this) }
        object ELEM_START : Roles() { override fun hashCode() = hashObject(this) }
        object LENGTH : Roles() { override fun hashCode() = hashObject(this) }
    }

    private sealed class Relational {
        data class LtVar(val x: TACSymbol.Var) : Relational(), SelfQualifier<LtVar> {
            override fun relates(v: TACSymbol.Var): Boolean = x == v

            override fun saturateWith(equivClasses: VariableSaturation): List<LtVar> {
                return equivClasses.invoke(x).map(Relational::LtVar)
            }
        }

        data class LeVar(val x: TACSymbol.Var) : Relational(), SelfQualifier<LeVar> {
            override fun relates(v: TACSymbol.Var): Boolean = v == x

            override fun saturateWith(equivClasses: VariableSaturation): List<LeVar> =
                equivClasses(x).map(Relational::LeVar)
        }

        data class EqVar(val x: TACSymbol.Var) : Relational(), SelfQualifier<EqVar> {
            override fun relates(v: TACSymbol.Var): Boolean = x == v

            override fun saturateWith(equivClasses: VariableSaturation): List<EqVar> =
                    equivClasses(x).map(Relational::EqVar)
        }

    }

    private val numericInterpreter = object : AbstractAbstractInterpreter<State, NumericState>() {

        private val mustBeConstant = MustBeConstantAnalysis(graph, NonTrivialDefAnalysis(graph, graph.cache.def))

        private val top = IQ.TOP

        private val expressionSemantics = object : NonRelationalExpressionInterpreter<NumericState, IQ, Any>() {
            override val nondet: IQ
                get() = top

            private val baseSemantics = object : StatelessUIntValueSemantics<IQ, IntValue>() {
                override fun lift(lb: BigInteger, ub: BigInteger): IntValue = IntValue.Interval(lb, ub)

                override fun lift(iVal: IntValue): IQ = IQ(x = iVal, qual = setOf())

                override val nondet: IQ
                    get() = top

            }

            @Suppress("DEPRECATION") // For John
            override val valueSemantics: IValueSemantics<NumericState, IQ, Any>
                    = object : QualifiedUIntApproxValueInterpreter<NumericState, Any, SelfQualifier<*>, IQ, IntValue>(
                    delegate = baseSemantics,
                    adapter = { it }
            ) {
                override fun evalAdd(
                        v1: IQ,
                        o1: TACSymbol,
                        v2: IQ,
                        o2: TACSymbol,
                        toStep: NumericState,
                        input: NumericState,
                        whole: Any,
                        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
                ): IQ {
                    val addQualifiers = if((v1.qual.contains(Roles.ARRAY_START) && v2.x.isConstant && v2.x.c == 0x20.toBigInteger()) ||
                            v2.qual.contains(Roles.ARRAY_START) && v1.x.isConstant && v1.x.c == 0x20.toBigInteger()) {
                        listOf(Roles.ELEM_START)
                    } else {
                        listOf()
                    }
                    return super.evalAdd(v1, o1, v2, o2, toStep, input, whole, l).let { res ->
                        if(v1.x.isConstant && v1.x.c == BigInteger.ONE) {
                            propagateSimpleZones(res, v1)
                        } else if(v2.x.isConstant && v2.x.c == BigInteger.ONE) {
                            propagateSimpleZones(res, v2)
                        } else {
                            res
                        }
                    }.let { res ->
                        if(addQualifiers.isNotEmpty()) {
                            return res.copy(qual = res.qual + addQualifiers)
                        } else {
                            res
                        }
                    }
                }
            }

            override fun stepAssignVar(lhs: TACSymbol.Var, s: TACSymbol.Var, toStep: NumericState, input: NumericState, whole: Any, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): NumericState {
                if(s == TACKeyword.MEM64.toVar() && l.ptr in allocSites.abstractAllocations) {
                    val sort = allocSites.abstractAllocations[l.ptr]!!.sort
                    if(sort is AllocationAnalysis.WithElementSize) {
                        return this.assign(
                                toStep, lhs, IQ(IntValue.Interval(lb = 0x80.toBigInteger()), qual = setOf(Roles.ARRAY_START)),
                                input, whole, l.wrapped
                        )
                    }
                }
                return super.stepAssignVar(lhs, s, toStep, input, whole, l)
            }

            private fun propagateSimpleZones(res: IQ, v2: IQ) : IQ {
                val q = v2.qual.filterIsInstance(Relational.LtVar::class.java).map { it ->
                    Relational.LeVar(it.x)
                }
                return if(q.isNotEmpty()) {
                    res.copy(qual = res.qual + q)
                } else {
                    res
                }
            }

            override fun assign(toStep: NumericState, lhs: TACSymbol.Var, newValue: IQ, input: NumericState, whole: Any, wrapped: LTACCmd): NumericState {
                return qualifierManager.assign(toStep, lhs, newValue, wrapped.ptr)
            }

            override fun interp(o1: TACSymbol, toStep: NumericState, input: NumericState, whole: Any, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): IQ {
                return when(o1) {
                    is TACSymbol.Const -> this.liftConstant(o1.value)
                    is TACSymbol.Var -> input[o1]?:mustBeConstant.mustBeConstantAt(l.ptr, o1)?.let {
                        IQ(IntValue.Constant(it), qual = setOf())
                    }?:nondet
                }
            }

            override fun liftConstant(value: BigInteger): IQ {
                return IQ(x = IntValue.Constant(value), qual = setOf())
            }

            override fun stepAssignConst(lhs: TACSymbol.Var, value: BigInteger, toStep: NumericState, input: NumericState, whole: Any, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): NumericState {
                if(value == BigInteger.ZERO) {
                    return this.assign(toStep, lhs, IQ(
                            IntValue.Constant(BigInteger.ZERO),
                            qual = input.keys.filter {
                                it != TACKeyword.MEMORY.toVar()
                            }.map {
                                Relational.LeVar(it)
                            }.toSet()
                    ), input, whole, l.wrapped)
                }
                return super.stepAssignConst(lhs, value, toStep, input, whole, l)
            }

        }

        override val pathSemantics: IPathSemantics<TreapMap<TACSymbol.Var, IQ>, State> =
                object : BoundedQIntPropagationSemantics<IQ, SelfQualifier<*>, NumericState, Any>(
                    object : QualifierPropagationComputation<IQ, NumericState, Any, SelfQualifier<*>>() {
                        override fun extractValue(op1: TACSymbol.Var, s: NumericState, w: Any, l: LTACCmd): IQ {
                            return s[op1] ?: top
                        }
                    }
                ) {
                    override fun assignVar(toStep: NumericState, lhs: TACSymbol.Var, toWrite: IQ, where: LTACCmd): NumericState {
                        return qualifierManager.assign(
                                toStep, lhs, toWrite, where.ptr
                        )
                    }

                    override fun propagateSummary(summary: TACSummary, s: NumericState, w: Any, l: LTACCmd): NumericState {
                        return s
                    }

                    override fun handlePath(pi: PathInformation<SelfQualifier<*>>, av: IQ, selfQuals: MutableList<SelfQualifier<*>>) {
                        if(pi is PathInformation.WeakLowerBound && pi.sym != null && av.qual.contains(Relational.LeVar(x = pi.sym))) {
                            selfQuals.add(Relational.EqVar(pi.sym))
                        }
                        if(pi is PathInformation.StrictUpperBound && pi.sym != null) {
                            selfQuals.add(Relational.LtVar(pi.sym))
                        }
                        if(pi is PathInformation.WeakUpperBound && pi.sym != null) {
                            selfQuals.add(Relational.LeVar(pi.sym))
                        }
                    }
                }

        private val qualifierManager = object : QualifierManager<SelfQualifier<*>, IQ, TreapMap<TACSymbol.Var, IQ>>(me = graph.cache.gvn) {
            override fun mapValues(s: TreapMap<TACSymbol.Var, IQ>, mapper: (TACSymbol.Var, IQ) -> IQ): TreapMap<TACSymbol.Var, IQ> {
                return s.updateValues { k, v -> mapper(k, v) }
            }

            override fun assignVar(toStep: TreapMap<TACSymbol.Var, IQ>, lhs: TACSymbol.Var, toWrite: IQ, where: CmdPointer): TreapMap<TACSymbol.Var, IQ> {
                return toStep + (lhs to toWrite)
            }

        }

        override val statementInterpreter: IStatementInterpreter<NumericState, State> = object : AbstractStatementInterpreter<NumericState, State>() {
            override fun forget(lhs: TACSymbol.Var, toStep: NumericState, input: NumericState, whole: State, l: LTACCmd): NumericState {
                return qualifierManager.assign(toStep, lhs, newValue = top, where = l.ptr)
            }

            override fun stepExpression(lhs: TACSymbol.Var, rhs: TACExpr, toStep: NumericState, input: NumericState, whole: State, l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): NumericState {
                return expressionSemantics.stepExpression(
                        lhs, rhs, toStep, input, whole, l
                )
            }

        }


        override fun killLHS(lhs: TACSymbol.Var, s: NumericState, w: State, narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>): TreapMap<TACSymbol.Var, IQ> {
            return s[lhs]?.let { iq ->
                qualifierManager.killLHS(lhs = lhs, lhsVal = iq, where = narrow.ptr, s = s)
            } ?: s
        }

        override fun postStep(stepped: NumericState, l: LTACCmd): TreapMap<TACSymbol.Var, IQ> = stepped

        override fun project(l: LTACCmd, w: State): NumericState = w.num

    }

    companion object {
        private val END_BLOCK = LVar.Instrumentation("END_BLOCK")
        private val WRITE = LVar.Instrumentation("WRITE")
        private val LENGTH = LVar.Instrumentation("LENGTH")

        private fun LinearInvariants.getEqualities() = (this matches {
            v("v1") {
                it is LVar.PVar
            } `=` v("v2") {
                it is LVar.PVar
            }
        }).mapToSet {
            (it.symbols["v1"] as LVar.PVar).v to (it.symbols["v2"] as LVar.PVar).v
        }
    }

    private val mustBeConstantAnalysis = MustBeConstantAnalysis(graph = graph, wrapped = NonTrivialDefAnalysis(graph))

    private fun stepCommand(st: State, ltacCmd: LTACCmd): Pair<State, Boolean> {
        val isLengthAssignRaw = ltacCmd.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.let { read ->
            st.isLengthAssign?.invoke(read)
        } ?: LengthReadResult.NONE
        val isLengthAssign = isLengthAssignRaw != LengthReadResult.NONE
        val nxtNum = numericInterpreter.step(ltacCmd, st).let { stepped ->
            if(!isLengthAssign) {
                stepped
            } else {
                val lhs = ltacCmd.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs
                val currIt = stepped[lhs] ?: IQ.TOP
                stepped + (lhs to currIt.copy(qual = currIt.qual + Roles.LENGTH))
            }
        }
        var seenLengthWrite = false
        val initInv = st.inv
        val nxtInv = when(ltacCmd.cmd) {
            is TACCmd.Simple.AssigningCmd -> {
                val killed = if(isLengthAssign) {
                    initInv.kill(ltacCmd.cmd.lhs).substitute(ltacCmd.cmd.lhs, sym = LENGTH)
                } else {
                    initInv.kill(ltacCmd.cmd.lhs)
                }
                if(ltacCmd.cmd.lhs == TACKeyword.MEM64.toVar() ) {
                    killed
                } else if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    val fvConst = TACExprFreeVarsCollector.getFreeVars(ltacCmd.cmd.rhs).mapNotNull {
                        LVar.PVar(it) `to?` mustBeConstantAnalysis.mustBeConstantAt(where = ltacCmd.ptr, v = it)
                    }.toMap()
                    killed + initInv.genFor(ltacCmd.cmd.lhs, ltacCmd.cmd.rhs).flatMap {
                        listOf(it, it.withConst(fvConst))
                    } + (LinearTerm.lift(ltacCmd.cmd.rhs)?.let { it ->
                        LinearEquality.build {
                            !ltacCmd.cmd.lhs `=` it
                        }
                    }?.let(::setOf) ?: setOf<LinearEquality>()) +
                        identifyTransform(ltacCmd.cmd.rhs, st).filter { it != ltacCmd.cmd.lhs }.map {
                            it to ltacCmd.cmd.lhs
                        }.let { eqs ->
                            killed.propagateEquality(eqs) { null }
                        }
                } else if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd.ByteStore && ltacCmd.cmd.loc is TACSymbol.Var && ltacCmd.cmd.base == TACKeyword.MEMORY.toVar()) {
                    val writtenSize = st.tryInterpAsConstant(ltacCmd.cmd.value)
                    if(st.seenLengthWrite == false && st.elemSize != null && initInv implies {
                                !ltacCmd.cmd.loc + !writtenSize  * st.elemSize + 0x20 `=` END_BLOCK
                            } && (st.constSize == null || writtenSize == st.constSize.asTACSymbol())) { // if we have a constant size, make sure what we're writing is the, you know, constant size
                        seenLengthWrite = true
                        killed
                    } else if(initInv implies { !ltacCmd.cmd.loc `=` WRITE }) {
                        killed.mapToTreapSet {
                            it.updateSynthetic(WRITE, 0x20.toBigInteger())
                        }
                    } else {
                        val underWrite = initInv matches underWritePattern(ltacCmd.cmd.loc)
                        if(underWrite.size == 1) {
                            val underAmount = underWrite.single().factors["diff"]!!
                            killed.mapToTreapSet {
                                it.updateSynthetic(WRITE, EVM_WORD_SIZE - underAmount)
                            }
                        } else {
                            killed
                        }
                    }
                } else {
                    killed
                }
            }
            is TACCmd.Simple.WithLongCopy -> {
                val targetOffset = ltacCmd.cmd.copy.dest.offset
                val targetBase = ltacCmd.cmd.copy.dest.base
                if(targetOffset is TACSymbol.Var && targetBase == TACKeyword.MEMORY.toVar() && initInv implies {
                            !targetOffset `=` WRITE
                        }) {
                    when(val len = ltacCmd.cmd.copy.length) {
                        is TACSymbol.Var -> {
                            val constAmount = st.num[len]?.x?.let {
                                if(it.isConstant) {
                                    it.c
                                } else {
                                    null
                                }
                            }
                            val toRet = treapSetBuilderOf<LinearEquality>()
                            for(it in initInv) {
                                toRet.add(it.updateSynthetic(WRITE, LVar.PVar(len)))
                                if (constAmount != null) {
                                    toRet.add(it.updateSynthetic(WRITE, constAmount))
                                }
                            }
                            /*
                               Find cases where we've just copied l bytes, where
                               l = array_len * k
                               according to our invariants. In that case, we expect to have the following invariant
                               after the above update synthetics:
                               WRITE - l + array_len * k = END

                               By propagating that l == array_len * k, the terms ancel, showing that WRITE = END,
                               AKA initialization is complete
                             */
                            val saturations = st.num.keysMatching { _, iq ->
                                Roles.LENGTH in iq.qual
                            }.takeIf { it.isNotEmpty() }?.let { lenVars ->
                                initInv.matches {
                                    len `=` k("scale") {
                                        it > BigInteger.ZERO
                                    } * v("l_var") {
                                        it is LVar.PVar && it.v in lenVars
                                    }
                                }.map {
                                    len to ((it.symbols["l_var"] as LVar.PVar).v to it.factors["scale"]!!)
                                }
                            }.orEmpty()
                            toRet.build().letIf(saturations.isNotEmpty()) {
                                it.propagateEqualities(saturations, listOf()) {
                                    null
                                }
                            }
                        }
                        is TACSymbol.Const -> initInv.map {
                            it.updateSynthetic(WRITE, len.value)
                        }
                    }.toTreapSet()
                } else {
                    initInv
                }
            }
            is TACCmd.Simple.AssumeCmd -> run {
                val assumeVar = ltacCmd.cmd.cond as? TACSymbol.Var ?: return@run initInv
                val iq = st.num[assumeVar] ?: return@run initInv
                iq.qual.filterIsInstance<IntQualifier.ConditionVar>().filter {
                    it.condition == ConditionQualifier.Condition.EQ
                }.mapNotNull {
                    (it.op1 as? TACSymbol.Var)?.`to?`(it.op2 as? TACSymbol.Var)
                }.let {
                    initInv.propagateEqualities(scaledEq = listOf(), eqs = it) { _ -> null }
                }
            }
            else -> initInv
        }
        var scratchState = st.mustScratch
        if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd && ltacCmd.cmd.lhs in scratchState) {
            scratchState = scratchState.toMutableSet()
            scratchState.remove(ltacCmd.cmd.lhs)
        }
        if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            scratchState = stepScratch(ltacCmd.narrow(), scratchState, st.mustScratch)
        }
        val seenClose = if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd && ltacCmd.cmd.lhs == TACKeyword.MEM64.toVar()) {
            st.seenAllocClose?.let { true }
        } else {
            st.seenAllocClose
        }
        return st.copy(
                num = nxtNum,
                inv = nxtInv,
                seenLengthWrite = st.seenLengthWrite?.let { it || seenLengthWrite },
                mustScratch = scratchState,
                seenAllocClose = seenClose
        ) to (isLengthAssignRaw == LengthReadResult.NON_ALIASING_BYTELOAD)
    }

    private fun isConstant(sym: TACSymbol, st: State, target: BigInteger): Boolean {
        return (sym is TACSymbol.Const && sym.value == target) ||
                (sym is TACSymbol.Var && st.num[sym]?.let {
                    it.x.isConstant && it.x.c == target
                } == true)
    }

    private fun State.tryInterpAsConstant(sym: TACSymbol) = when(sym) {
        is TACSymbol.Const -> sym
        is TACSymbol.Var -> this.num[sym]?.x?.takeIf { it.isConstant }?.c?.asTACSymbol() ?: (this.inv matchesAny {
            sym `=` k("k")
        })?.factors?.get("k")?.asTACSymbol() ?: sym
    }

    private fun isLengthWrite(l: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>, st: State) : Boolean =
        st.seenLengthWrite == false && st.elemSize != null && st.inv implies {
            !l.cmd.loc + !st.tryInterpAsConstant(l.cmd.value) * st.elemSize + 0x20 `=` END_BLOCK
        }

    private fun identifyTransform(exp: TACExpr, s: State) : Set<TACSymbol.Var> {
        if(exp !is TACExpr.BinExp) {
            return setOf()
        }
        if(!exp.operandsAreSyms()) {
            return setOf()
        }
        val toRet = mutableSetOf<TACSymbol.Var>()
        val o1 = exp.o1AsTACSymbol()
        val o2 = exp.o2AsTACSymbol()
        when(exp) {
            is TACExpr.Vec.Add -> {
                if(isConstant(o1, s, BigInteger.ZERO) && o2 is TACSymbol.Var) {
                    toRet.add(o2)
                }
                if(isConstant(o2, s, BigInteger.ZERO) && o1 is TACSymbol.Var) {
                    toRet.add(o1)
                }
            }
            is TACExpr.Vec.Mul -> {
                if(isConstant(o1, s, BigInteger.ONE) && o2 is TACSymbol.Var) {
                    toRet.add(o2)
                }
                if(isConstant(o2, s, BigInteger.ONE) && o1 is TACSymbol.Var) {
                    toRet.add(o1)
                }
            }
            else -> {}
        }
        return toRet
    }

    /**
     * Given the set of pointers in [scratchState] that MUST alias a scratch pointer, after stepping [ltac],
     * what is the set of scratch pointers that must alias the scratch pointer. It is basically gen kill,
     * with gen defined as:
     *
     * gen([x = y]) = {x} if y in scratchState else {}
     * gen([x = y + d]) = {x} if y in scratchState /\ d !in scratchState else {}
     * gen([x = d + y]) as above
     *
     * gen([x = y - x]) = {x} if y in scratchState /\ x !in scratchState
     *
     * gen(e) = {} otherwise
     */
    private fun stepScratch(ltac: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>,
                            scratchState: Set<TACSymbol.Var>, st: Set<TACSymbol.Var>): Set<TACSymbol.Var> {
        val toMut by lazy {
            if(scratchState === st) {
                st.toMutableSet()
            } else {
                // TODO CERT-1742: we could avoid these gymnastics by switching to TreapSet
                @Suppress("DontDowncastCollectionTypes")
                scratchState as MutableSet<TACSymbol.Var>
            }
        }
        val rhs = ltac.cmd.rhs
        if(rhs is TACExpr.BinOp.Sub && rhs.operandsAreSyms()) {
            val o1 = rhs.o1AsTACSymbol()
            val o2 = rhs.o2AsTACSymbol()

            if(o1 in st && o2 !in st) {
                toMut.add(ltac.cmd.lhs)
                return toMut
            }
        } else if(rhs is TACExpr.Vec.Add && rhs.operandsAreSyms()) {
            val o1 = rhs.o1AsTACSymbol()
            val o2 = rhs.o2AsTACSymbol()
            if ((o1 in st && o2 !in st) || (o2 in st && o1 !in st)) {
                toMut.add(ltac.cmd.lhs)
                return toMut
            }
        } else if(rhs is TACExpr.Sym.Var && rhs.s in st) {
            toMut.add(ltac.cmd.lhs)
            return toMut
        } else if(ltac.ptr in allocSites.scratchReads) {
            toMut.add(ltac.cmd.lhs)
            return toMut
        }
        return scratchState
    }

    private class CancelSortException(s: String) : Exception(s)
}

object SimpleInitializationAnalysis {
    data class FourByteWrite(
            val fourByte: BigInteger,
            val base: TACSymbol.Var
    )

    data class Result(
        val closePoints: Map<CmdPointer, Set<AllocationAnalysis.AbstractLocation>>,
        val closeEdges: Map<Pair<NBId, NBId>, Set<AllocationAnalysis.AbstractLocation>>,
        val zeroWriteMarkers: Set<CmdPointer>,
        val fourByteWrite: Map<CmdPointer, FourByteWrite>,
        val failurePoints: Set<CmdPointer>,
        val markTopLocs: Set<CmdPointer>,
        val markDefiniteBounds: Set<CmdPointer>,
        val inferredLoopBounds: Map<NBId, BigInteger>
    )

    fun analyze(g: TACCommandGraph, alloc: AllocationInformation) = ParallelPool.allocInScope(2000, {timeout -> Z3BlasterPool(z3TimeoutMs = timeout)}) {
        SimpleInitializationAnalysisWorker(g, alloc, it).analyze()
    }
}
