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

import algorithms.SCCGraph
import algorithms.topologicalOrder
import allocator.Allocator
import analysis.*
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import com.certora.collect.*
import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.CodeRemapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import log.*
import report.DummyLiveStatsReporter
import solver.SolverResult
import spec.rules.EquivalenceRule
import spec.rules.IRule
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACProgramCombiners.andThen
import vc.data.TACProgramCombiners.flatten
import vc.data.TACProgramCombiners.toCore
import vc.data.state.TACValue
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.getFreeVars
import verifier.TACVerifier
import verifier.Verifier
import verifier.equivalence.StandardInstrumentationConfig.toAView
import verifier.equivalence.StandardInstrumentationConfig.toBView
import verifier.equivalence.data.*
import verifier.equivalence.data.CallableProgram.Companion.map
import verifier.equivalence.loops.LoopConditionExtractor
import verifier.equivalence.summarization.ComputationResults
import verifier.equivalence.summarization.PureFunctionExtraction
import verifier.equivalence.tracing.BufferTraceInstrumentation
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.`=`
import verifier.equivalence.tracing.BufferTraceInstrumentation.Companion.isResultCommand
import java.math.BigInteger

/**
 * An isomorphism between the inputs of the loop in A and the loop in B. That is, if (v1, v2) are in
 * the list, it means `v1` and `v2` hold the same value when the loop is reached in A and B respectively.
 */
private typealias LoopInputEquiv = List<Pair<ProgramValueLocation, ProgramValueLocation>>

private val logger = Logger(LoggerTypes.EQUIVALENCE)

class LoopEquivalence<R: PureFunctionExtraction.CallingConvention<R>> private constructor(
    val ruleGenerator: AbstractRuleGeneration<R>,
    val queryContext: EquivalenceQueryContext,
    private val loopA: LoopInProgram<MethodMarker.METHODA, R>,
    private val loopB: LoopInProgram<MethodMarker.METHODB, R>
) {

    private data class LoopInputEquivalence(
        val equiv: List<Pair<ProgramValueLocation, ProgramValueLocation>>,
        val loopAConst: TaggedMap<MethodMarker.METHODA, ProgramValueLocation, BigInteger>,
        val loopBConst: TaggedMap<MethodMarker.METHODB, ProgramValueLocation, BigInteger>
    )

    /**
     * Captures the effect of some iteration of the loop.
     * [inputs] are the program value locations which are definitely read during loop execution
     * before the being written.
     * [writesAtExit] are writes to program value locations which definitely occur before the exit (that is, the jump to the
     * [AnnotatedLoop.distinguishedExit]).
     * [aliasAtExit] are variables at the loop exit which are always aliases of some variable at the start of the loop
     * iteration (and thus, when replacing the loop, should be replaced with that alias).
     * [invariant] is the transition invariant for reaching the loop head expressed in [LoopConfig.head] (or [AnnotatedLoop.head]);
     * see [LoopInvariant].
     *
     * Finally, [allWrites] is the set of possible all writes seen during execution of a single iteration of the loop;
     * this is necessarily a superset of [writesAtExit], and is used to infer what values to havoc
     * when replacing the loop.
     */
    private interface LoopIterationInfo {
        val inputs: Set<ProgramValueLocation>
        val writesAtExit: TreapSet<ProgramValueLocation>
        val aliasAtExit: Map<TACSymbol.Var, TACSymbol.Var>
        val invariant: LoopInvariant
        val allWrites: Set<ProgramValueLocation>
    }

    /**
     * A potential view of the loop iteration effect. [head] is the "effective" head of the loop. For `do` style loops,
     * this will just be the head of the loop. For while loops whose first execution of the loop condition is always
     * true, this is the "rotated" loop head: the successor of the loop condition.
     *
     * The fields of [LoopIterationInfo] are interpreted as being collected from some execution starting
     * from [head].
     */
    private data class LoopConfig(
        val head: NBId,
        override val inputs: Set<ProgramValueLocation>,
        override val writesAtExit: TreapSet<ProgramValueLocation>,
        override val aliasAtExit: Map<TACSymbol.Var, TACSymbol.Var>,
        override val invariant: LoopInvariant,
        override val allWrites: Set<ProgramValueLocation>
    ) : LoopIterationInfo

    /**
     * Used as the "calling convention" for a single loop iteration; used
     * by the [ruleGen] to bind equivalent inputs. [LoopInvariant] is a (disjunctive)
     * loop invariant which is assumed at the start of the loop body. [constantValues]
     * are loop values that are known to be constant in the loop body and which are definitely
     * not changed during loop iteration (as inferred by [inferAlwaysConstant]).
     *
     * [inputLocs] is an arbitrary ordering on the [LoopIterationInfo.inputs] for the loop.
     * These are bound to the [propehcyVariables]. Care is taken that the two loop bodies being compared
     * use "equivalent orderings", that is, the first element in the inputs list for loop A is known to
     * be equal to the first element of the list for loop B, and similarly for all elements.
     *
     * By using the same [propehcyVariables] to bind the [inputLocs] of both A and B, we thus
     * model that the loops always start their iterations in the same state.
     */
    private data class IterationBinding(
        val invariant: LoopInvariant,
        val inputLocs: List<ProgramValueLocation>,
        val constantValues: Map<ProgramValueLocation, BigInteger>
    )

    /**
     * Indicates that this loop in [M] is equivalent to a loop in [M]'s partner.
     * The loop is identified by its source head [loopHead] (NOT it's effective head).
     * The designated loop successor is in [loopSucc].
     *
     * [loopInputs] and [loopOutputs] are the correlated loop inputs and outputs. The order in these
     * lists matter; if this object represents the result for loop A, then the elements
     * of [loopInputs] are pointwise paired with the corresponding [EquivalentLoop.loopInputs] of
     * the [EquivalentLoop] object of B. A similar relationship holds for [loopOutputs].
     *
     * [aliases] are program value locations mutated within the loop body which are actually
     * always equal to the value of some variable at the head of the loop; this relationship
     * should be materialized when the loop is replaced. [toHavoc] are all program value locations
     * mutated by the loop body which are not a [loopOutputs] or [aliases]; to soundly model the
     * loop removal, they should be havoced.
     */
    data class EquivalentLoop<M: MethodMarker>(
        val loopHead: NBId,
        val loopInputs: List<ProgramValueLocation>,
        val loopOutputs: List<ProgramValueLocation>,
        val toHavoc: Collection<ProgramValueLocation>,
        val aliases: Map<TACSymbol.Var, TACSymbol.Var>,
        val loopSucc: NBId
    )

    /**
     * A transition invariant for the loop. [initial] and [inductiveState] have equal domains.
     * This invariant expresses that either the head of the loop is reached with the
     * variables in [initial] having the given values, OR the loop head is reached
     * via a backjump, with the pc [inductivePC] and the state predicate given by [inductiveState].
     *
     * In other words, this is denoted into:
     * ```
     * (forall (v, c) in initial.v == c) \/ (
     *    inductivePC /\
     *      forall (v, e) in inductiveState.v == e
     * )
     * ```
     *
     * the domains in `forall` are static, and they should be read as expanding to a series of conjunctions.
     *
     * the inductive state is expected to refer to "pre" loop versions, these are dummy names which may or may not
     * be constrained by inductivePC.
     */
    private data class LoopInvariant(
        val initial: TreapMap<TACSymbol.Var, BigInteger>,
        val inductiveState: TreapMap<TACSymbol.Var, TACExpr>,
        val inductivePC: Set<TACExpr>
    )

    /**
     * Expresses the possible "view" of the [loop] in the program. [mainConfig] is the straightforward interpretation
     * of the loop starting from the syntactic head of the loop. [alternative] is the view from a rotated version of
     * the loop, if possible (see the [LoopConfig.head] for a discussion of loop rotation).
     *
     * [distinguishedExit] is the distinguished exit block of the loop; the sole successor block that does not revert.
     */
    private data class LoopConfigOptions(
        val loop: Loop,
        val mainConfig: LoopConfig,
        val alternative: LoopConfig?,
        val distinguishedExit: NBId
    )

    /**
     * A [sourceLoop] with an inferred effective [head] (which may or may not be the same as the [Loop.head] of [sourceLoop]),
     * and whose exit is [distinguishedExit]. [config] provides the information about the effects of a single iteration of the
     * loop.
     */
    private data class AnnotatedLoop(
        val sourceLoop: Loop,
        val head: NBId,
        val distinguishedExit: NBId,
        val config: LoopConfig
    ) : LoopIterationInfo by config

    /**
     * Situates an [annotatedLoop] within some [program] which we know how to call.
     */
    private data class LoopInProgram<M: MethodMarker, R: PureFunctionExtraction.CallingConvention<R>>(
        val annotatedLoop: AnnotatedLoop,
        val program: CallableProgram<M, R>
    )

    /**
     * Tags a loop variable (a [ProgramValueLocation]) with its identity, whether the variable is in the
     * A or B version of the loop [isA], and its index [ind] in some collection; indicating
     * it is the Nth value in the collection of loop inputs/loop outputs/etc.
     */
    @KSerializable
    data class LoopVarI(
        val isA: Boolean,
        val ind: Int
    ) : AmbiSerializable {
        companion object {
            val META_KEY : MetaKey<LoopVarI> = MetaKey<LoopVarI>("loop.equivalence.var")
        }
    }

    /**
     * An annotation payload for recording [which] and keeping it alive (keeping it from being folded).
     * [loopVarId] serves as a redundant record of the [LoopVarI] which is expected to be attached to
     * [which] (we've see that meta get mysteriously erased, alas)
     */
    @KSerializable
    data class KeepAliveInt(
        val which: TACSymbol.Var,
        val loopVarId: LoopVarI?
    ) : AmbiSerializable, TransformableVarEntityWithSupport<KeepAliveInt> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): KeepAliveInt {
            return KeepAliveInt(f(which), loopVarId)
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(which)

        companion object {
            val META_KEY = MetaKey<KeepAliveInt>("loop.equivalence.keep-me")
        }
    }

    /**
     * The initial inputs to the loop. When showing loop equivalence, we start in some state where we hypothesize
     * that input variables (a1, a2, ...) of A are pointwise equal to the input variables (b1, b2, ...) of loop B.
     * We express this equality by binding a1 and b1 to some nondeterminstic value I1, and similarly for a2, b2, and I2, etc.
     *
     * These variables are the Ii variables to which we bind ai and bi.
     */
    val propehcyVariables = List(minOf(loopA.annotatedLoop.inputs.size, loopB.annotatedLoop.inputs.size)) {
        TACSymbol.Var(
            "init!$it",
            Tag.Bit256,
            NBId.ROOT_CALL_ID,
            MetaMap(TACMeta.NO_CALLINDEX) + (inputPosition to it)
        )
    }

    /**
     * A version of the program in [loopA] which forces control flow to reach the loop.
     */
    private val truncatedActp by lazy {
        instrumentNoReturns(loopA)
    }

    /**
     * Ditto, but for [loopB].
     */
    private val truncatedBctp by lazy {
        instrumentNoReturns(loopB)
    }

    /**
     * Callback which transforms a partially extracted loop body to [R] (currently always a [CoreTACProgram]).
     */
    private fun interface ResultCB<R> {
        /**
         * Generates an [R] from the partially extracted loop body. All backjumps in the loop have been rewritten to
         * jump to [backJump], and all control flow to the distinguished exit of the loop flows to [exitBlock].
         * [code] contains all code reachable from the head of the loop *without* traversing a backjump OR an edge leading to
         * the distinguished exit block. Thus, revert blocks reachable from the body of the loop, while technically not part of the loop
         * body proper are included in [code]. [graph] contains the control-flow grapy of [code] in which [backJump] and [exitBlock]
         * are both sinks. [code] does NOT contain a mapping for [exitBlock] and [backJump]; it is the responsibility of the
         * caller to fill those in.
         */
        fun toResult(graph: BlockGraph, code: Map<NBId, List<TACCmd.Simple>>, exitBlock: NBId, backJump: NBId) : R
    }

    /**
     * Starting from the head of [loop], extract "one unrolling" of the loop body, leaving to [toResult] the details of how to
     * model the backjump and loop exit. NB that [toResult] could actually itself invoke [extractLoopBody] to use
     * as the backjump model, effectively unrolling the loop multiple times. Early versions of this code
     * actually did multiple unrollings, and the implementation choices made to support that are preserved here to
     * enable easily reviving that.
     */
    private fun <R, M: MethodMarker> extractLoopBody(
        loop: LoopInProgram<M, *>,
        toResult: ResultCB<R>
    ) : R {
        val startBlock = loop.annotatedLoop.head
        val origGraph = loop.program.program.analysisCache.graph
        val blockGraph = MutableBlockGraph()
        val blockCode = mutableMapOf<NBId, List<TACCmd.Simple>>()

        val exitBlock = Allocator.getNBId()
        val backJump = Allocator.getNBId()

        val remapper = CodeRemapper<Unit>(
            blockRemapper = CodeRemapper.BlockRemappingStrategy r@{ nbId, _, ctxt, _ ->
                /**
                 * *VERY* important to only rewrite references to the start block in the successor context; if we didn't
                 * we'll end up rewriting the start block identity to be `backJump` and all jumps back to the head
                 * to POINT to `backJump`, meaning we'll end up with a loop.
                 */
                if(ctxt == CodeRemapper.BlockRemappingStrategy.RemappingContext.SUCCESSOR && nbId == startBlock) {
                    return@r backJump
                /**
                 * Instead of exiting, jump to the dedicated exit block.
                 */
                } else if(nbId == loop.annotatedLoop.distinguishedExit) {
                    return@r exitBlock
                } else {
                    nbId
                }
            },
            /**
             * No other remapping needed.
             */
            variableMapper = { _, v -> v },
            idRemapper = { _ ->
                { _, id, _ ->
                    id
                }
            },
            callIndexStrategy = { _, callIndex, _ -> callIndex }
        )

        /**
         * Traverse the loop body in some order, depth I think?
         */
        val worklist = arrayDequeOf(startBlock)

        val visited = mutableSetOf<NBId>()

        worklist.consume { currBlock ->
            if(!visited.add(currBlock)) {
                return@consume
            }
            if(currBlock == loop.annotatedLoop.distinguishedExit) {
                return@consume
            } else {
                val blockCmds = loop.program.program.code[currBlock]!!
                val remapped = blockCmds.map(remapper.commandMapper(Unit))
                val origSucc = origGraph.blockGraph[currBlock]!!
                val succs = origSucc.updateElements {
                    remapper.remapBlockId(Unit, CodeRemapper.BlockRemappingStrategy.RemappingContext.SUCCESSOR, it)
                }
                blockGraph[currBlock] = succs
                blockCode[currBlock] = remapped
                /**
                 * For reasons that are complicated and annoying, it is very helpful to have a dedicated dummy
                 * command at the start of revert blocks.
                 *
                 * In particular, once we support vyper, it is useful to treat "the start of any revert block" as a
                 * garbage collection point; however the GC tracking assumes there is some distinguished command that
                 * serves as the point to start GC, and if the start of the revert block is a memory command we get
                 * inconsistent instrumentation. Thus, we simply add a "start of revert block" dummy command, which our
                 * instrumentation is happy to pick up and use as the dummy GC point it is.
                 */
                if(currBlock in loop.program.program.analysisCache.revertBlocks) {
                    blockCode[currBlock] = listOf(TACCmd.Simple.LabelCmd("synthetic")) + blockCode[currBlock]!!
                }
                origSucc.filterTo(worklist) {
                    it != startBlock && it != loop.annotatedLoop.distinguishedExit
                }
            }
        }
        return toResult.toResult(
            graph = blockGraph,
            code = blockCode,
            backJump = backJump,
            exitBlock = exitBlock
        )
    }

    /**
     * Enumerate possible pairings of values. The identity of the domain of this pairing
     * is [T], and the codom is [U]. It is expected these values correspond to some loop variable;
     * the valuation of these loop variables is given by [aAssign] and [bAssign].
     *
     * For each loop variable in [aAssign] (which has some index `i`), this function finds all possible loop
     * variables in [bAssign] which have the same value; these potential paired values each have some index `j`.
     * The pairing of these values is generated by [genPair] which receives the indices `i` and `j`,
     * and returns the paired [T] and [U] values representing the pairing of "loop value i in A" and "loop value j in B".
     *
     * The collection of all such possible pairings (each represented as a list of pairs of [T] and [U])
     * is lazily built via the [SequenceScope] receiver.
     *
     * Implementation details follow
     * [ind] is the next index for which we want to find pairings. It is **required** that `LoopVarI(isA = true, ind = ind)`
     * exists in [aAssign]. [nextAssign] is incrementally built pairing between loop variables (again, represented by a pair
     * of [T] and [U]). When a candidate is fully enumerated, [nextAssign] is yielded to the [SequenceScope] receiver.
     * [allowSkip] indicates whether the pairing can omit elements of [aAssign] which have no partner in [bAssign].
     * Thus, [nextAssign] is a fully constructed pairing if [ind] has exhausted all candidates in [aAssign] or
     * all of the candidates in [bAssign] have been assigned (as expressed by [usedBIndices]).
     *
     * if [allowSkip] is false, and [aAssign] and [bAssign] are of equal cardinality, and [genPair] is a
     * faithful injection of `i` and `j` into the domains [T] and [U], then the resulting pairing
     * generated by this function will be an isomorphism between [T] and [U]. Otherwise,
     * the pairing will be a one-to-one mapping from some subset of [T] to [U].
     */
    private suspend fun <T, U> SequenceScope<List<Pair<T, U>>>.enumerate(
        allowSkip: Boolean,
        ind: Int,
        aAssign: Map<LoopVarI, TACValue>,
        bAssign: Map<LoopVarI, TACValue>,

        nextAssign: List<Pair<T, U>>,
        usedBIndices: BigInteger,

        genPair: (Int, Int) -> Pair<T, U>,
    ) {
        if(ind == aAssign.size || usedBIndices.bitCount() == bAssign.size) {
            yield(nextAssign)
            return
        }
        val unmatchedValue = aAssign[LoopVarI(isA = true, ind = ind)] ?: return
        for(i in 0 until bAssign.size) {
            if(usedBIndices.testBit(i)) {
                continue
            }
            val bValue = bAssign[LoopVarI(isA = false, ind = i)]!!
            if(bValue != unmatchedValue) {
                continue
            }
            val newPairing = genPair(ind, i)
            enumerate(allowSkip, ind + 1, aAssign, bAssign, nextAssign + newPairing, usedBIndices.setBit(i), genPair)
        }
        if(allowSkip) {
            enumerate(allowSkip, ind + 1, aAssign, bAssign, nextAssign, usedBIndices, genPair)
        }
    }

    /**
     * Generates a VC that asserts [s] is always equal to [c] when control-flow reaches
     * [loop] in [prog], and then check with SMT. The verifier result is returned.
     *
     * NB that the calling convention type parameter is given the `Any?` bound: we don't bind any arguments, effectively
     * treating inputs to the program as non-deterministic.
     */
    private suspend fun generateAndAssertEq(
        prog: CallableProgram<*, *>,
        loop: Loop,
        s: ProgramValueLocation,
        c: BigInteger
    ) : Verifier.VerifierResult {
        val assertEq = TACKeyword.TMP(Tag.Bit256, "!res")
        val newBlock = CommandWithRequiredDecls(listOf(
            when(s) {
                is ProgramValueLocation.MemoryCell -> {
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = assertEq,
                        loc = s.idx.asTACSymbol(),
                        base = TACKeyword.MEMORY.toVar()
                    )
                }
                is ProgramValueLocation.StackVar -> {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = assertEq,
                        rhs = s.which
                    )
                }
            },
            TACCmd.Simple.AnnotationCmd(
                KeepAliveInt.META_KEY,
                KeepAliveInt(
                    which = assertEq,
                    loopVarId = null
                )
            )
        ), assertEq) andThen ExprUnfolder.unfoldPlusOneCmd("assert", TXF { assertEq eq c}) {
            TACCmd.Simple.AssertCmd(
                it.s, "equal to $c"
            )
        }

        val patcher = prog.program.toPatchingProgram()
        replaceLoopIn(
            prog.program,
            patcher,
            loop,
            newBlock
        )
        val sceneId = queryContext.scene.toIdentifiers()
        val withAssertion = patcher.toCode(prog.program).let {
            it.prependToBlock0(CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = TACKeyword.MEMORY.toVar(),
                    rhs = TACExpr.MapDefinition(Tag.ByteMap) {
                        TACExpr.Unconstrained(Tag.Bit256)
                    }
                )
            ))
        }.copy(name = "eq-const-at-start")
        return TACVerifier.verify(
            scene = sceneId, tacObject = withAssertion, rule = EquivalenceRule.freshRule("always-const"), liveStatsReporter = DummyLiveStatsReporter
        )
    }

    /**
     * Infers whether value [s] is always some constant when control flow reaches [loop] in [c].
     *
     * NB that this is trivially easy to infer if [s] is a [ProgramValueLocation.StackVar];
     * this is mostly used for inferring memory locations.
     *
     * If no such constant value is inferred, null is returned.
     */
    private suspend fun inferAlwaysConstant(
        c: CallableProgram<*, *>,
        loop: Loop,
        s: ProgramValueLocation
    ) : BigInteger? {
        val res = generateAndAssertEq(
            prog = c,
            s = s,
            loop = loop,
            c = BigInteger.ZERO
        )
        // unbelievably lucky...
        if(res.finalResult == SolverResult.UNSAT) {
            return BigInteger.ZERO
        }
        val newCand = res.tac.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(KeepAliveInt.META_KEY)
        }.mapNotNull {
            res.firstExampleInfo.model.valueAsBigInteger(it.which).leftOrNull()
        }.toList().singleOrNull() ?: return null
        val secondTry = generateAndAssertEq(
            prog = c,
            s = s,
            loop = loop,
            c = newCand
        )
        if(secondTry.finalResult != SolverResult.UNSAT) {
            return null
        }
        return newCand
    }

    /**
     * From a counter example in [res], find all of the [KeepAliveInt] annotations which have non-null
     * [KeepAliveInt.loopVarId], and parse the concrete values of the symbol in [KeepAliveInt].
     *
     * The values are partitioned by [LoopVarI.isA], and then the [LoopVarI] of the [KeepAliveInt]
     * are associated with the concrete values from the counter example.
     *
     * In other words, assuming the program recorded loop variable values via [KeepAliveInt],
     * this function returns the valuation of loop A's [LoopVarI] and loop B's [LoopVarI] values.
     */
    private fun parseKeepAlives(
        res: Verifier.VerifierResult
    ) : Pair<TaggedMap<MethodMarker.METHODA, LoopVarI, TACValue>, TaggedMap<MethodMarker.METHODB, LoopVarI, TACValue>> {
        val exampleInfo = res.examplesInfo.first()
        return res.tac.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(KeepAliveInt.META_KEY)?.takeIf {
                it.loopVarId != null
            }
        }.map {
            it.loopVarId!! to (exampleInfo.model.tacAssignments[it.which] ?: error("no value for $it"))
        }.toList().partition {
            it.first.isA
        }.let { (aVals, bVals) ->
            aVals.toMap() to bVals.toMap()
        }
    }

    /**
     * Generate loop [LoopInputEquiv], that is, potential pairings between loop input variables.
     * Let the input variables of [loopA] (recorded in the [LoopInputs.holders] of [inputA]) be Ia;
     * and let Ib be defined similarly for [loopB] and [inputB].
     *
     * We then generate isomorphisms m: Ia -> Ib where `m` is a plausible pairing of "equal" loop variables.
     * That is, we query SMT to see if, at the start of the respective loops, the following
     * equation holds:
     * `forall v in Ia.v == m(v)`
     * As `Ia` is finite, the above should be read as expanding to a sequence of conjunctions. If this assertion holds,
     * this means the two programs MUST reach the two loops in states where each variable `v` in Ia *always* has
     * the same value as m(v), it's paired partner in loop B. This does not mean this pairing is invariant, as
     * the following example demonstrates:
     *
     * ```
     * i = 0;
     * j = 0;
     * while(i < 10) {
     *    i++;
     *    j+=2;
     * }
     * ```
     *
     * ```
     * a = 0;
     * b = 0;
     * while(a < 10) {
     *    a++;
     *    b+=2;
     * }
     * ```
     *
     * There are two possible pairings (i = a, j = b) and (i = b, j = a), but only the first one is a loop invariant;
     * the latter no longer is true after a single iteration of the loop.
     *
     * This is precisely why we are enumerating possible isomorphisms instead of stopping at the first one; there
     * may be may plausible pairings, but we expect only one to be an invariant.
     */
    private suspend fun FlowCollector<LoopInputEquiv>.loopInputInference(
        assign: List<Pair<LoopValueAndHolder<MethodMarker.METHODA>, LoopValueAndHolder<MethodMarker.METHODB>>>,
        inputA: LoopInputs<MethodMarker.METHODA, R>,
        inputB: LoopInputs<MethodMarker.METHODB, R>
    ) {
        val aWithAssign = inputA.instrumented
        val bWithAssign = inputB.instrumented
        val equalityVC = generatePairingEqualityVC(
            assign
        )
        val rule = ruleGenerator.generateRule(
            aWithAssign, bWithAssign, equalityVC, "loop-input-infer"
        )
        val res = TACVerifier.verify(
            queryContext.scene.toIdentifiers(), rule.code, DummyLiveStatsReporter, IRule.createDummyRule("check-loop-equiv")
        )
        if(res.finalResult == SolverResult.UNSAT) {
            val assignment = assign.map { (a, b) ->
                a.value to b.value
            }
            ctxtDebug {
                "Found equal loop inputs: $assignment"
            }
            emit(assignment)
            return
        }
        val (aVals, bVals) = parseKeepAlives(res)

        sequence {
            enumerate(false, 0, aVals, bVals, listOf(), BigInteger.ZERO) { fstInd, sndInd ->
                inputA.holders[fstInd] to inputB.holders[sndInd]
            }
        }.forEach { nxt ->
            ctxtDebug {
                "Found new permutation: $nxt"
            }
            loopInputInference(nxt, inputA, inputB)
        }
    }

    /**
     * If [trunc] is a version of the program in [loop] where control flow has been forced to reach the start
     * of the loop, infer which [ProgramValueLocation] in the [LoopIterationInfo.inputs] (if any) have a constant value at the start of the
     * loop, and which are not mutated during loop execution. The mapping of these "actually always constant" loop inputs
     * to the values is returnd as a map.
     */
    private suspend fun <M: MethodMarker> inferConstInputs(
        trunc: CallableProgram<M, *>,
        loop: LoopInProgram<M, *>
    ) : TaggedMap<M, ProgramValueLocation, BigInteger> {
        val actuallyConst = loop.annotatedLoop.inputs.filter {
            it !in loop.annotatedLoop.allWrites
        }.mapNotNull {
            it `to?` inferAlwaysConstant(
                trunc,
                loop = loop.annotatedLoop.sourceLoop,
                s = it
            )
        }.toMap()
        return actuallyConst
    }

    /**
     * Representation of loop inputs for in [M] for pairing inference.
     * [constVal] are inputs to the loop which have been inferred to be constant (via [inferConstInputs]).
     * [holders] is some arbitrary ordering on the remaining inputs, paired with an instrumentation variable
     * (as represented by [LoopValueAndHolder.reprVar]).
     *
     * [instrumented] is a truncated version of the program in [M], where control flow is forced to reach the loop head;
     * however the loop (and all its successors) have been replaced with code that moves the values
     * in [LoopValueAndHolder.value] to the representative variables.
     */
    private data class LoopInputs<M: MethodMarker, R>(
        val constVal: TaggedMap<M, ProgramValueLocation, BigInteger>,
        val holders: List<LoopValueAndHolder<M>>,
        val instrumented: CallableProgram<M, R>
    )

    /**
     * Tag the program value location in a [LoopValueAndHolder], setting up a stable, non-indexed variable to hold
     * its value which is tagged with the appropriate [LoopVarI] meta according to [ind] and [m].
     */
    private fun <M: MethodMarker> ProgramValueLocation.tagValue(
        ind: Int,
        m: M
    ) : LoopValueAndHolder<M> {
        val instrName = if(m.isA) {
            "A"
        } else {
            "B"
        }
        val instrVarName = "LoopVal$instrName$ind"
        val instrVar = TACSymbol.Var(
            namePrefix = instrVarName,
            tag = Tag.Bit256,
            meta = MetaMap(TACMeta.NO_CALLINDEX) +
                (LoopVarI.META_KEY to LoopVarI(isA = m.isA, ind = ind)),
            callIndex = NBId.ROOT_CALL_ID
        )
        return LoopValueAndHolder(
            value = this,
            reprVar = instrVar
        )
    }

    /**
     * Given [truncated], a version of the [M] program with tag [l] where control flow has been forced
     * to reach the start of the loop, construct the [LoopInputs] object which records
     * the non-constant loop inputs into instrumentation variables.
     */
    private fun <M: MethodMarker> setupInputs(
        tag: M,
        l: LoopInProgram<M, R>,
        truncated: CallableProgram<M, R>,
        c: TaggedMap<M, ProgramValueLocation, BigInteger>
    ) : LoopInputs<M, R> {
        val nonConst = l.annotatedLoop.inputs.filter {
            it !in c
        }.mapIndexed { i, pvl ->
            pvl.tagValue(i, tag)
        }
        val instr = truncated.map { core ->
            core.patching { patcher ->
                replaceLoopIn(
                    ctp = core,
                    l = l.annotatedLoop.sourceLoop,
                    patcher = patcher,
                    replacement = nonConst.map { it.record() }.flatten()
                )
            }
        }
        return LoopInputs(
            constVal = c,
            holders = nonConst,
            instrumented = instr
        )
    }

    /**
     * Thin wrapper around [loopInputInference], which generates possible pairings between loop A and loop B
     * inputs, and packages them with the constant values inferred for the loop A and B inputs (if applicable).
     */
    private fun getInitialAssignmentGenerator(
        inputsA: LoopInputs<MethodMarker.METHODA, R>,
        inputsB: LoopInputs<MethodMarker.METHODB, R>
    ) : Flow<LoopInputEquivalence> {
        check(inputsA.holders.size == inputsB.holders.size) {
            "Mismatched input sizes"
        }
        val initial = inputsA.holders.zip(
            inputsB.holders
        )
        return flow {
            loopInputInference(
                initial,
                inputsA,
                inputsB
            )
        }.map { binding ->
            LoopInputEquivalence(
                loopAConst = inputsA.constVal,
                loopBConst = inputsB.constVal,
                equiv = binding
            )
        }
    }

    /**
     * Tries to find the minimal *actual* inputs to loops; this minimization is done when the loop input sizes do not match. If
     * the input sizes *do* match, then no minimization is attempted. Otherwise, the larger set of inputs is searched for
     * constant inputs via [inferConstInputs]. If, after removing constant inputs from A or B, the input domains still do not match in size,
     * we give up. Otherwise, the two truncated programs are instrumented to record the input values via [setupInputs]; the
     * program value to instrumentation variables and constant inputs are also recorded in the returned [LoopInputs] objects.
     */
    private suspend fun getMinimizedLoopInputs(): Pair<LoopInputs<MethodMarker.METHODA, R>, LoopInputs<MethodMarker.METHODB, R>>? {
        return if(loopA.annotatedLoop.inputs.size == loopB.annotatedLoop.inputs.size) {
            setupInputs(
                MethodMarker.METHODA,
                c = mapOf(),
                l = loopA,
                truncated = truncatedActp
            ) to setupInputs(
                MethodMarker.METHODB,
                c = mapOf(),
                l = loopB,
                truncated = truncatedBctp
            )
        } else if(loopA.annotatedLoop.inputs.size > loopB.annotatedLoop.inputs.size) {
            val loopAConst = inferConstInputs(
                truncatedActp,
                loopA
            )
            if(loopA.annotatedLoop.inputs.count {
                    it !in loopAConst
                } != loopB.annotatedLoop.inputs.size) {
                ctxtDebug {
                    "After constant input inference for A ${loopAConst}, still have mismatched domains: ${loopA.annotatedLoop.inputs} vs ${loopB.annotatedLoop.inputs}"
                }
                return null
            }
            setupInputs(
                tag = MethodMarker.METHODA,
                truncated = truncatedActp,
                l = loopA,
                c = loopAConst
            ) to setupInputs(
                tag = MethodMarker.METHODB,
                truncated = truncatedBctp,
                l = loopB,
                c = mapOf()
            )
        } else {
            val loopBConst = inferConstInputs(
                truncatedBctp,
                loopB
            )
            if(loopB.annotatedLoop.inputs.count { it !in loopBConst } != loopA.annotatedLoop.inputs.size) {
                ctxtDebug {
                    "After constant input inference for B ${loopBConst}, still have mismatched domains: ${loopA.annotatedLoop.inputs} vs ${loopB.annotatedLoop.inputs}"
                }
                return null
            }
            setupInputs(
                tag = MethodMarker.METHODA,
                truncated = truncatedActp,
                c = mapOf(),
                l = loopA
            ) to setupInputs(
                tag = MethodMarker.METHODB,
                truncated = truncatedBctp,
                l = loopB,
                c = loopBConst
            )
        }
    }

    /**
     * Version of [extractLoopBody] where the exit block is rerouted to a block whose contents are given by
     * [exitGen], and backjumps are rerouted to a block whose contents are generated by [backjumpGen].
     *
     * [loopVars] and [constantValues] are the loop inputs and constant valuations inferred by [loopInputInference]
     * and [inferConstInputs] resp. Together with the [AnnotatedLoop.invariant] of the [AnnotatedLoop]
     * of [data], this forms the [IterationBinding] of the returned [CallableProgram], providing a way to use
     * the extracted loop body in an equivalence check.
     */
    private fun <M: MethodMarker> extractLoopBody(
        data: LoopInProgram<M, *>,
        loopVars: List<ProgramValueLocation>,
        constantValues: Map<ProgramValueLocation, BigInteger>,
        exitGen: () -> CommandWithRequiredDecls<TACCmd.Simple>,
        backjumpGen: () -> CommandWithRequiredDecls<TACCmd.Simple>
    ) : CallableProgram<M, IterationBinding> {
        val genBlock = exitGen()
        val k1Copy = extractLoopBody(data, ResultCB { graph, code, exitBlock, backJump ->
            val finalGraph = MutableBlockGraph()
            finalGraph.putAll(graph)
            finalGraph[exitBlock] = treapSetOf()
            finalGraph[backJump] = treapSetOf()
            val backJumpBlock = backjumpGen()
            val codeWithSinks = code + (exitBlock to genBlock.cmds) + (backJump to backJumpBlock.cmds)
            CoreTACProgram(
                name = "extracted loop",
                code = codeWithSinks,
                blockgraph = finalGraph,
                check = true,
                instrumentationTAC = InstrumentationTAC(UfAxioms.empty()),
                symbolTable = data.program.program.symbolTable.mergeDecls(backJumpBlock.varDecls).mergeDecls(genBlock.varDecls),
                procedures = setOf()
            )
        })

        val callConv = IterationBinding(
            inputLocs = loopVars,
            invariant = data.annotatedLoop.invariant,
            constantValues = constantValues
        )

        return object : CallableProgram<M, IterationBinding> {
            override val program: CoreTACProgram
                get() = k1Copy
            override val callingInfo: IterationBinding
                get() = callConv

            override fun pp(): String {
                return "Loop body in ${data.program.pp()}"
            }

        }
    }

    /**
     * Wrapper around [extractLoopBody] which replaces an exit with [ComputationResults] with the payload (0),
     * and a backjump with [ComputationResults] with the payload (1, v0, v1, ...) where `vi` are the values
     * in [vars].
     *
     * [vars] (and thus `vi`) are the loop input variables for the loop in A or B. Wlog, let's say they are the
     * inputs of A, that is, a0, a1, ... etc. Then, they are paired with some of the inputs of B: b0, b1, ... etc.
     *
     * Via [ruleGen], these variables (ai, bi) are initialized to the same values. Further, here we are creating
     * the syntheic "result" summary [ComputationResults] with the payload `(1, a0, a1, ...)` in the body
     * of loop A and a similar summary with the payload `(1, b0, b1, ...)` in the body of B. If these programs
     * verify as equal, it means that, on equal inputs, both loops jump back to their heads AND all the pairs `(ai, bi)`
     * are still equal. In other words, we have shown that `forall i.ai == bi` is an invariant of the loop. Further,
     * we have shown that if one loop backjumps, the other backjumps; and that if one loop exits, the other also exits
     * (via the synthetic [ComputationResults] summary with payload 0 we insert at the exit).
     *
     * Note that we will *not* show that the loops exit in equal states; nor is this what we want. We expect that only some subset
     * of the values computed during loop execution are relevant post loop execution; inferring what values those are and
     * the correspondence between them for A and B is a task for later.
     */
    private fun <M: MethodMarker> basicInvariantLoopBody(
        o: LoopInProgram<M, R>,
        vars: List<ProgramValueLocation>,
        constantValues: Map<ProgramValueLocation, BigInteger>
    ) : CallableProgram<M, IterationBinding> {
        return extractLoopBody(o, vars, constantValues = constantValues, exitGen = { ->
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.LabelCmd("Exit loop body"),
                /**
                 * Basically a marker picked up by the [BufferTraceInstrumentation] saying "we exited the loop"
                 */
                TACCmd.Simple.SummaryCmd(
                    summ = ComputationResults(listOf(TACSymbol.Zero))
                )
            ))
        }) { ->
            val resultArgs = mutableListOf<TACSymbol.Var>()
            val commands = mutableListOf<TACCmd.Simple>(
                TACCmd.Simple.LabelCmd("backjump")
            )
            for (l in vars) {
                when (l) {
                    is ProgramValueLocation.MemoryCell -> {
                        val temp = TACKeyword.TMP(Tag.Bit256, "resultVariable")
                        resultArgs.add(temp)
                        commands.add(
                            TACCmd.Simple.AssigningCmd.ByteLoad(
                                lhs = temp,
                                base = TACKeyword.MEMORY.toVar(),
                                loc = l.idx.asTACSymbol()
                            )
                        )
                    }

                    is ProgramValueLocation.StackVar -> {
                        resultArgs.add(l.which)
                    }
                }
            }
            /**
             * A marker picked up by the [BufferTraceInstrumentation] saying "we backjumped, and our state
             * is now v0, v1, ..."
             */
            commands.add(
                TACCmd.Simple.SummaryCmd(
                    ComputationResults(
                        resultArgs + listOf(TACSymbol.One)
                    )
                )
            )
            CommandWithRequiredDecls(
                commands, resultArgs
            )
        }
    }

    /**
     * Rule generator which knows how to bind [IterationBinding] for loops. The calling convention of the functions
     * *containing* these loops is irrelevant: by the time we use this we have already shown that the loops
     * are in the same initial state.
     */
    private val ruleGen by lazy {
        object : AbstractRuleGeneration<IterationBinding>(
            context = queryContext
        ) {
            private val theSize = TACSymbol.Var("theCalldataSize", Tag.Bit256, NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))

            /**
             * Mostly for debugging purposes.
             */
            override fun setupCode(): CommandWithRequiredDecls<TACCmd.Simple> {
                return CommandWithRequiredDecls(
                    propehcyVariables.map {
                        TACCmd.Simple.AnnotationCmd(KeepAliveInt.META_KEY, KeepAliveInt(it, null))
                    },
                    propehcyVariables
                )
            }

            override fun <T : MethodMarker> annotateInOut(
                inlined: CoreTACProgram,
                callingConv: IterationBinding,
                context: ProgramContext<T>,
                callId: Int
            ): CoreTACProgram {
                return inlined
            }

            /**
             * Bind the locations in [IterationBinding.inputLocs] to the [propehcyVariables],
             * set the constant values of [IterationBinding.constantValues], and assume the [IterationBinding.invariant].
             */
            override fun <T : MethodMarker> generateInput(
                p: CallableProgram<T, IterationBinding>,
                context: ProgramContext<T>,
                callId: CallId
            ): CommandWithRequiredDecls<TACCmd.Simple> {
                val args = p.callingInfo.inputLocs
                val cmds = args.zip(propehcyVariables) { input, argSym ->
                    when (input) {
                        is ProgramValueLocation.MemoryCell -> {
                            TACCmd.Simple.AssigningCmd.ByteStore(
                                loc = input.idx.asTACSymbol(),
                                base = TACKeyword.MEMORY.toVar(callId),
                                value = argSym
                            )
                        }

                        is ProgramValueLocation.StackVar -> {
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = input.which.at(callId),
                                rhs = argSym.asSym()
                            )
                        }
                    }
                }
                val constBindings = p.callingInfo.constantValues.map { (k, v) ->
                    when(k) {
                        is ProgramValueLocation.MemoryCell -> {
                            val base = TACKeyword.MEMORY.toVar(callId)
                            CommandWithRequiredDecls(
                                TACCmd.Simple.AssigningCmd.ByteStore(
                                    loc = k.idx.asTACSymbol(),
                                    value = v.asTACSymbol(),
                                    base = base
                                ), base
                            )
                        }
                        is ProgramValueLocation.StackVar -> {
                            val indexed = k.which.at(callId)
                            CommandWithRequiredDecls(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = indexed,
                                    rhs = v.asTACSymbol()
                                ), indexed
                            )
                        }
                    }
                }.flatten()

                val varsToAdd = mutableSetOf<TACSymbol.Var>()
                val initConstraint = p.callingInfo.invariant.initial.map { (k, v) ->
                    val id = k.at(callId)
                    varsToAdd.add(id)
                    TXF { id eq v }
                }.fold(TACSymbol.True.asSym() as TACExpr) { a, b ->
                    TACExpr.BinBoolOp.LAnd(a, b)
                }

                /**
                 * Used to index the variables that occur in the [IterationBinding.invariant].
                 * Has the side effect of recording the variable generated in varsToAdd, because
                 * life is short.
                 */
                val callIndexer = object : DefaultTACExprTransformer() {
                    override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
                        val at = exp.s.at(callId)
                        varsToAdd.add(at)
                        return at.asSym()
                    }
                }

                val inductiveConstraint = (p.callingInfo.invariant.inductivePC.map {
                    callIndexer.transform(it)
                } + p.callingInfo.invariant.inductiveState.map {
                    TXF { it.key.at(callId) eq callIndexer.transform(it.value) }
                }).fold(TACSymbol.True.asSym() as TACExpr) { a, b ->
                    TACExpr.BinBoolOp.LAnd(a, b)
                }
                val temp = TACKeyword.TMP(Tag.Bool, "!inductiveAssume")
                varsToAdd.add(temp)
                val inductiveAssume = CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = temp,
                            rhs = TACExpr.BinBoolOp.LOr(inductiveConstraint, initConstraint)
                        ),
                        TACCmd.Simple.AssumeCmd(temp, "startup")
                    ), varsToAdd
                )
                return CommandWithRequiredDecls(
                    cmds,
                    propehcyVariables.toSet() + TACKeyword.MEMORY.toVar(callId)
                ) andThen inductiveAssume andThen (TACKeyword.CALLDATASIZE.toVar(callId) `=` theSize).merge(theSize) andThen constBindings
            }

        }
    }

    /**
     * An iteration strategy which reasons about exits (Results) only, and where we have an oracle saying that for every
     * exit site `e` in A, we know that control flow must exit via one of b1, b2, ... in B. This relationship between
     * e and bi is expressed in [exitMatching]; the first element of the pair is e, the second element bi. It is assumed
     * (but not checked) that the domain of this mapping covers all possible exits from A. It is likewise assumed (but not checked)
     * that all of command pointers referenced here count as a result event for the [BufferTraceInstrumentation], i.e.,
     * [BufferTraceInstrumentation.Companion.isResultCommand] returns true.
     *
     * [ind] indicates which exit site `e` we are checking; equivalence checking is complete when [ind] exhausts the paired
     * exit sites in [exitMatching].
     */
    private class MatchedExitIteration<R>(val ind: Int, val exitMatching: List<Pair<CmdPointer, Collection<CmdPointer>>>) : ExplorationManager<R> {
        override fun nextConfig(p: EquivalenceChecker.PairwiseProofManager): ExplorationManager.EquivalenceCheckConfiguration<R> {
            val (aSites, bSites) = exitMatching[ind]
            val confA = StandardInstrumentationConfig.configure(
                v = p.toAView(),
                t = BufferTraceInstrumentation.TraceTargets.Results,
                inc = BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(aSites)
            )
            val confB = StandardInstrumentationConfig.configure(
                v = p.toBView(),
                t = BufferTraceInstrumentation.TraceTargets.Results,
                inc = BufferTraceInstrumentation.TraceInclusionMode.UntilExactly(bSites.toSet())
            )
            return object : ExplorationManager.EquivalenceCheckConfiguration<R> {


                override val vcGenerator: TraceEquivalenceChecker.VCGenerator<R>
                    get() = ProgramExitTracer
                override val minimizer: Minimizer?
                    get() = null
                override val traceTarget: BufferTraceInstrumentation.TraceTargets
                    get() = BufferTraceInstrumentation.TraceTargets.Results

                override fun getAConfig(): BufferTraceInstrumentation.InstrumentationControl {
                    return confA
                }

                override fun getBConfig(): BufferTraceInstrumentation.InstrumentationControl {
                    return confB
                }
            }
        }

        override fun onTimeout(check: TraceEquivalenceChecker.CheckResult<R>): ExplorationManager<R>? {
            return null
        }

        override fun onSuccess(check: TraceEquivalenceChecker.CheckResult<R>): ExplorationManager<R>? {
            if(ind == exitMatching.lastIndex) {
                return null
            }
            return MatchedExitIteration(ind + 1, exitMatching)
        }

    }

    /**
     * Responsible for actually running the equivalence check on the loop bodies extracted via [basicInvariantLoopBody],
     * using [ruleGen]. If possible, we use the [MatchedExitIteration] strategy to make reasoning considerably easier.
     */
    private inner class LoopEquivalenceVerifier(
        progA: CallableProgram<MethodMarker.METHODA, IterationBinding>,
        progB: CallableProgram<MethodMarker.METHODB, IterationBinding>,
        matchedExits: Map<CmdPointer, Collection<CmdPointer>>?
    ) : FullEquivalence<IterationBinding, Unit>(
        queryContext = queryContext,
        rule = EquivalenceRule.freshRule("loop body equivalence"),
        programA = progA,
        programB = progB,
        ruleGenerator = ruleGen
    ) {
        private val strategy = matchedExits?.let {
            MatchedExitIteration<IterationBinding>(0, it.entries.map { (k, v) -> k to v })
        } ?: StandardExplorationStrategy.ExitSiteTracerPointerwise(
            currSite = 0,
            aSites = progA.program.parallelLtacStream().filter {
                it.cmd.isResultCommand()
            }.map { it.ptr }.toList()
        )

        suspend fun run() = equivalenceLoop(strategy)

        override fun explainStorageCEX(
            res: TraceEquivalenceChecker.CheckResult<IterationBinding>,
            f: StorageStateDiff<IterationBinding>
        ) { }

        override fun explainTraceCEX(
            targets: BufferTraceInstrumentation.TraceTargets,
            a: TraceEvent<MethodMarker.METHODA>?,
            b: TraceEvent<MethodMarker.METHODB>?,
            res: TraceEquivalenceChecker.CheckResult<IterationBinding>
        ) { }
    }

    /**
     * Debugging functions with extra context
     */
    private fun ctxtString() = "LA: ${loopA.annotatedLoop.head} in ${loopA.program.pp()} & LB: ${loopB.annotatedLoop.head} in ${loopB.program.pp()}"

    private fun ctxtDebug(f: () -> Any) {
        logger.debug {
            "${ctxtString()}: ${f()}"
        }
    }

    private fun ctxtInfo(f: () -> Any) {
        logger.info {
            "${ctxtString()}: ${f()}"
        }
    }

    /**
     * Proves that the two loops in [loopA] and [loopB] are equivalent in their behavior, as expressed by the
     * returned pair of [EquivalentLoop]. If this returns null, the loop equivalence checking process failed; and
     * the loops could not be judged equivalent.
     */
    private suspend fun proveInvariance() : Pair<EquivalentLoop<MethodMarker.METHODA>, EquivalentLoop<MethodMarker.METHODB>>? {
        ctxtInfo {
            "Trying to find correlation to loop bodies"
        }
        val (inputA, inputB) = getMinimizedLoopInputs() ?: return run {
            ctxtDebug {
                "Failed to infer loop input correlation"
            }
            null
        }
        val correlationGen = getInitialAssignmentGenerator(inputA, inputB)
        return correlationGen.map { correlation ->
            ctxtInfo {
                "Basic correlation complete: $correlation"
            }
            val (aVars, bVars) = correlation.equiv.unzip()

            val progA = basicInvariantLoopBody(loopA, aVars, correlation.loopAConst)
            val progB = basicInvariantLoopBody(loopB, bVars, correlation.loopBConst)

            ctxtInfo {
                "Basic VC construction done, trying to match exits..."
            }

            val matchedExits = ExitMatcher.matchExits(
                scene = queryContext.scene,
                ruleGeneration = ruleGen,
                progB = progB,
                progA = progA
            )

            ctxtInfo {
                "Exit match complete, have non-null result? ${matchedExits != null}"
            }

            val checker = LoopEquivalenceVerifier(
                progA, progB, matchedExits
            )
            val res = checker.run()
            ctxtInfo {
                "Checking complete: solver result: ${res.pp()}"
            }
            if(res !is FullEquivalence.EquivalenceResult.Verified) {
                return@map null
            }
            ctxtInfo {
                "Searching for exit sites"
            }
            val correlatedExits = getExitCorrelation(
                correlation
            )
            ctxtInfo {
                "Found exit correlation: $correlatedExits"
            }
            val (aOut, bOut) = correlatedExits.unzip()
            EquivalentLoop<MethodMarker.METHODA>(
                loopHead = loopA.annotatedLoop.sourceLoop.head,
                loopOutputs = aOut,
                loopInputs = aVars,
                toHavoc = loopA.annotatedLoop.writesAtExit - aOut - loopA.annotatedLoop.aliasAtExit.map { (k, _) ->
                    ProgramValueLocation.StackVar(k)
                },
                loopSucc = loopA.annotatedLoop.distinguishedExit,
                aliases = loopA.annotatedLoop.aliasAtExit
            ) to EquivalentLoop<MethodMarker.METHODB>(
                loopHead = loopB.annotatedLoop.sourceLoop.head,
                loopInputs = bVars,
                loopOutputs = bOut,
                toHavoc = loopB.annotatedLoop.writesAtExit - bOut - loopB.annotatedLoop.aliasAtExit.map { (k, _) ->
                    ProgramValueLocation.StackVar(k)
                },
                loopSucc = loopB.annotatedLoop.distinguishedExit,
                aliases = loopB.annotatedLoop.aliasAtExit
            )
        }.firstOrNull {
            /**
             * This is a "collecting" operation, so the flow will be lazily produced until it is exhausted
             * or the first non-null result is produced (and this is exactly what we want)
             */
            it != null
        }
    }

    /**
     * Pairs a program value location [value] which holds some import part of the loop state
     * with an instrumentation variable [reprVar] which will hold the value of said variable.
     */
    private data class LoopValueAndHolder<M: MethodMarker>(
        val value: ProgramValueLocation,
        val reprVar: TACSymbol.Var
    ) {
        init {
            check(LoopVarI.META_KEY in reprVar.meta) {
                "representative var $reprVar does not have a loop identity tag"
            }
        }
    }

    /**
     * Filters the [AnnotatedLoop.writesAtExit] in [l] for those that are not provably dead,
     * and gives back a list of [LoopValueAndHolder] for these locations for us in exit correlation.
     */
    private fun <M: MethodMarker> getLiveExitWrites(
        m: M,
        l: LoopInProgram<M, *>
    ) : List<LoopValueAndHolder<M>> {
        val loopExitBlock = l.annotatedLoop.distinguishedExit
        val hostGraph = l.program.program.analysisCache.graph
        val lastCmd = hostGraph.elab(loopExitBlock).commands.first()
        return l.annotatedLoop.writesAtExit.filter { v ->
            when(v) {
                is ProgramValueLocation.MemoryCell -> true
                is ProgramValueLocation.StackVar -> {
                    hostGraph.cache.lva.isLiveBefore(lastCmd.ptr, v.which) && v.which !in l.annotatedLoop.aliasAtExit
                }
            }
        }.mapIndexed { ind, pvl ->
            pvl.tagValue(
                ind, m
            )
        }
    }

    /**
     * Records the value of [LoopValueAndHolder.value] into [LoopValueAndHolder.reprVar], and
     * keeps the value alive via a [KeepAliveInt] annotation.
     */
    private fun LoopValueAndHolder<*>.record(): CommandWithRequiredDecls<TACCmd.Simple> {
        val holder = reprVar
        return when(value) {
            is ProgramValueLocation.MemoryCell -> {
                CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = holder,
                        loc = value.idx.asTACSymbol(),
                        base = TACKeyword.MEMORY.toVar()
                    ),
                    setOf(TACKeyword.MEMORY.toVar(), holder)
                )
            }
            is ProgramValueLocation.StackVar -> {
                CommandWithRequiredDecls(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = holder,
                        rhs = value.which.asSym()
                    ),
                    setOf(
                        holder, value.which
                    )
                )
            }
        } andThen CommandWithRequiredDecls(TACCmd.Simple.AnnotationCmd(
            KeepAliveInt.META_KEY,
            KeepAliveInt(
                holder,
                holder.meta[LoopVarI.META_KEY]!!
            )
        ))
    }

    /**
     * Extract the loop body of [l], requiring control flow to exit the loop, and recording the
     * values in [exitVars] into the instrumentation values. [inputVars] and [constantValues]
     * are used for the [IterationBinding] of the returned [CallableProgram]
     */
    private fun <M: MethodMarker> getExitCorrelationBody(
        l: LoopInProgram<M, *>,
        inputVars: List<ProgramValueLocation>,
        constantValues: Map<ProgramValueLocation, BigInteger>,
        exitVars: List<LoopValueAndHolder<M>>,
    ) : CallableProgram<M, IterationBinding> {
        return extractLoopBody(
            data = l,
            loopVars = inputVars,
            constantValues = constantValues,
            exitGen = {
                exitVars.map { ev ->
                    ev.record()
                }.flatten()
            }
        ) {
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssumeCmd(TACSymbol.False, "no backjump")
                )
            )
        }.map {
            it.assumeOnlyLoopExit()
        }
    }

    /**
     * Utility function used in the above, prune any path that doesn't go to the block
     * we generated in [getExitCorrelation] (identified by the presence of [KeepAliveInt]
     * annotations).
     */
    private fun CoreTACProgram.assumeOnlyLoopExit() : CoreTACProgram {
        val g = this.analysisCache.graph
        val exitSites = this.getEndingBlocks()
        // really a sanity check that we can find the block in this CTP which
        // is the normal "exit" from the loop
        val distinguishedExit = exitSites.single { eSite ->
            g.elab(eSite).commands.any {
                it.maybeAnnotation(KeepAliveInt.META_KEY) != null
            }
        }
        return exitSites.filter {
            it != distinguishedExit
        }.stream().patchForEach(this, check = true) { toRewrite ->
            // doesn't matter what command we're trampling; we're not taking this path
            val lastCmd = g.elab(toRewrite).commands.last().ptr
            replaceCommand(lastCmd, listOf(
                TACCmd.Simple.AssumeCmd(TACSymbol.False, "no exit except loop exit")
            ))
        }
    }

    /**
     * Given the mapping between [loopA] and [loopB] input states which has now been proven to be an invariant,
     * try to infer a mapping between the state variables of [loopA] and [loopB] at the loop exit,
     * such that variables `a` and `b` in A and B respectively will always have the same value at the loop exit.
     *
     * This is best effort; any unpaired variables will be havoced in the loop replacement step.
     */
    private suspend fun getExitCorrelation(correlation: LoopInputEquivalence): List<Pair<ProgramValueLocation, ProgramValueLocation>> {
        val (aInputs, bInputs) = correlation.equiv.unzip()

        /**
         * Get the state variables which are probably live at the loop exits; these are are candidates for exit equivalence.
         */
        val aLive = getLiveExitWrites(MethodMarker.METHODA, loopA)
        val bLive = getLiveExitWrites(MethodMarker.METHODB, loopB)
        if(aLive.isEmpty() || bLive.isEmpty()) {
            return listOf()
        }

        /**
         * Instrument loopA and loopB to force the loop to exit, and record the values in aLive and bLive.
         */
        val exitProgramA = getExitCorrelationBody(
            l = loopA,
            inputVars = aInputs,
            exitVars = aLive,
            constantValues = correlation.loopAConst
        )
        val exitProgramB = getExitCorrelationBody(
            l = loopB,
            inputVars = bInputs,
            exitVars = bLive,
            constantValues = correlation.loopBConst
        )

        /**
         * Then use the same enumeration trick we used in input inference
         */
        val initialAssign = mutableListOf<Pair<LoopValueAndHolder<MethodMarker.METHODA>, LoopValueAndHolder<MethodMarker.METHODB>>>()
        var i = 0
        while(i < aLive.size && i < bLive.size) {
            initialAssign.add(aLive[i] to bLive[i])
            i++
        }
        return inferAndCheckExitCorrelation(
            aLive,
            bLive,
            initialAssign,
            exitProgramA,
            exitProgramB
        )

    }

    /**
     * Given pairing between values in A and values in B, generate a program which asserts that the paired values
     * are equal. It is assumed (but not checked) that this VC will be used in a program where the values in
     * the [LoopValueAndHolder] have been moved into the [LoopValueAndHolder.reprVar]
     */
    private fun generatePairingEqualityVC(
        l: List<Pair<LoopValueAndHolder<MethodMarker.METHODA>, LoopValueAndHolder<MethodMarker.METHODB>>>
    ) : CoreTACProgram {
        return l.map { (aVal, bVal) ->
            val t = aVal.reprVar.tag
            /**
             * Defeat any attempts at constant evaluation; we need a counterexample, so statically
             * proving something false is not helpful.
             */
            TACExpr.BinRel.Eq(
                TACExpr.Apply(TACBuiltInFunction.OpaqueIdentity(t), listOf(aVal.reprVar.asSym()), t),
                TACExpr.Apply(TACBuiltInFunction.OpaqueIdentity(t), listOf(bVal.reprVar.asSym()), t)
            )
        }.reduce(TACExpr.BinBoolOp::LAnd).let { conjunction ->
            val assertion = tmp("equalityAssert", Tag.Bool)
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = assertion,
                    rhs = conjunction
                ),
                TACCmd.Simple.AssertCmd(
                    o = assertion,
                    description = "Loop values equal"
                )
            ))
        }.toCore("assertion-program", Allocator.getNBId())
    }

    /**
     * The exit version of [loopInputInference]; however here we allow elements of [aLive] to be skipped,
     * meaning the returned pairing isn't a isomorphism, nor necessarily onto.
     */
    private suspend fun inferAndCheckExitCorrelation(
        aLive: List<LoopValueAndHolder<MethodMarker.METHODA>>,
        bLive: List<LoopValueAndHolder<MethodMarker.METHODB>>,
        initialAssign: List<Pair<LoopValueAndHolder<MethodMarker.METHODA>, LoopValueAndHolder<MethodMarker.METHODB>>>,
        exitProgramA: CallableProgram<MethodMarker.METHODA, IterationBinding>,
        exitProgramB: CallableProgram<MethodMarker.METHODB, IterationBinding>
    ): List<Pair<ProgramValueLocation, ProgramValueLocation>> {
        if(initialAssign.isEmpty()) {
            return listOf()
        }
        val vcProgram = generatePairingEqualityVC(initialAssign)

        val rule = ruleGen.generateRule(
            exitProgramA,
            exitProgramB,
            vcProgram,
            "loop-exit-equiv"
        )

        val res = TACVerifier.verify(
            queryContext.scene.toIdentifiers(), rule.code, DummyLiveStatsReporter, IRule.createDummyRule("check-output-equiv")
        )
        if(res.finalResult == SolverResult.UNSAT) {
            return initialAssign.map { (a, b) ->
                a.value to b.value
            }
        }

        val (aVals, bVals) = parseKeepAlives(res)
        val nxt = sequence {
            enumerate(true, 0, aVals, bVals, listOf(), BigInteger.ZERO) { fstInd, sndInd ->
                aLive[fstInd] to bLive[sndInd]
            }
        }.firstOrNull()
        if(nxt == null) {
            logger.debug { "Could not find a plausible loop output assignment from CEX" }
            return listOf()
        }
        logger.debug {
            "Found new permutation: $nxt"
        }
        return inferAndCheckExitCorrelation(
            aLive,
            bLive,
            nxt,
            exitProgramA,
            exitProgramB
        )
    }

    companion object {
        private fun tmp(nm: String, t: Tag = Tag.Bit256) = TACSymbol.Var(
            "loopEquiv$nm", tag = t
        ).toUnique("!")

        /**
         * Replace the [loop] in [p] with a sound summary. This replacement is done in the context of an equivalence
         * query, and thus the loop body is replaced with an application of a deterministic UF to the loop inputs;
         * the [EquivalentLoop.loopOutputs] are bound to the result of this UF application. [EquivalentLoop.toHavoc]
         * are havoced, and [EquivalentLoop.aliases] are replaced with assignments that reflect the aliasing relationship.
         */
        private fun <M: MethodMarker, R> deleteLoop(
            p: CallableProgram<M, R>,
            loop: EquivalentLoop<M>
        ) : CallableProgram<M, R> {
            return p.map { c ->
                val patcher = c.toPatchingProgram("patch")
                val (args, bind) = loop.loopInputs.mapIndexed { ind, a ->
                    val loopArg = tmp("arg!$ind")
                    loopArg to when(a) {
                        is ProgramValueLocation.MemoryCell -> {
                            CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssigningCmd.ByteLoad(
                                    lhs = loopArg,
                                    base = TACKeyword.MEMORY.toVar(),
                                    loc = a.idx.asTACSymbol()
                                )
                            ), TACKeyword.MEMORY.toVar(), loopArg)
                        }
                        is ProgramValueLocation.StackVar -> {
                            CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = loopArg,
                                    rhs =  a.which
                                )
                            ), loopArg, a.which)
                        }
                    }
                }.unzip().mapSecond {
                    it.flatten()
                }
                val havocs = loop.toHavoc.map {
                    val havoc = tmp("havoc")
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                            lhs = havoc,
                        ),
                        when(it) {
                            is ProgramValueLocation.MemoryCell -> {
                                TACCmd.Simple.AssigningCmd.ByteStore(
                                    base = TACKeyword.MEMORY.toVar(),
                                    loc = it.idx.asTACSymbol(),
                                    value = havoc
                                )
                            }
                            is ProgramValueLocation.StackVar -> {
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = it.which,
                                    rhs = havoc
                                )
                            }
                        }
                    ), havoc)
                }.flatten()

                val outputBind = loop.loopOutputs.mapIndexed { index, where ->
                    val outputVar = tmp( "loopOutput$index")
                    val arity = args.size + 1
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = outputVar,
                            rhs = TACExpr.Apply(
                                TACBuiltInFunction.NondetFunction(arity),
                                args.map { it.asSym() } + index.asTACExpr,
                                Tag.Bit256
                            )
                        ),
                        when(where) {
                            is ProgramValueLocation.MemoryCell -> {
                                TACCmd.Simple.AssigningCmd.ByteStore(
                                    loc = where.idx.asTACSymbol(),
                                    base = TACKeyword.MEMORY.toVar(),
                                    value = outputVar
                                )
                            }
                            is ProgramValueLocation.StackVar -> {
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = where.which,
                                    rhs = outputVar
                                )
                            }
                        }
                    ), outputVar)
                }.flatten()

                val aliasBind = loop.aliases.map { (lhs, rhs) ->
                    CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = lhs,
                            rhs = rhs.asSym()
                        )
                    ), lhs, rhs)
                }.flatten()



                val loopReplacementBlock = TACCmd.Simple.LabelCmd("Start loop summarization") andThen
                    bind andThen
                    havocs andThen
                    outputBind andThen
                    aliasBind andThen
                    TACCmd.Simple.LabelCmd("End loop summarization") andThen
                    TACCmd.Simple.JumpCmd(
                        loop.loopSucc
                    )
                replaceLoopIn(
                    c,
                    patcher,
                    head = loop.loopHead,
                    replacement = loopReplacementBlock
                )
                patcher.toCode(c)
            }
        }

        /**
         * Try to find the "first" loop reachable in [a] and [b], and prove their equivalence. If equivalence
         * is proven, the loops are replaced with a sound summary via [deleteLoop], and the process repeats
         * until equivalence fails (or there are no more loops).
         */
        private suspend fun <R: PureFunctionExtraction.CallingConvention<R>> loopSummarizationIteration(
            ruleGeneration: AbstractRuleGeneration<R>,
            queryContext: EquivalenceQueryContext,
            a: CallableProgram<MethodMarker.METHODA, R>,
            b: CallableProgram<MethodMarker.METHODB, R>
        ) : Pair<CallableProgram<MethodMarker.METHODA, R>, CallableProgram<MethodMarker.METHODB, R>> {
            ArtifactManagerFactory().dumpCodeArtifacts(a.program.copy(name = "MethodA"), ReportTypes.EQUIVALENCE_DEBUG, StaticArtifactLocation.Reports, DumpTime.AGNOSTIC)
            ArtifactManagerFactory().dumpCodeArtifacts(b.program.copy(name = "MethodB"), ReportTypes.EQUIVALENCE_DEBUG, StaticArtifactLocation.Reports, DumpTime.AGNOSTIC)
            val loopAConfigs = extractPrimaryLoop(a.program) ?: return (a to b)
            val loopBConfigs = extractPrimaryLoop(b.program) ?: return (a to b)

            val (aConfig, bConfig) = if((loopAConfigs.alternative == null) != (loopBConfigs.alternative == null)) {
                if(loopAConfigs.alternative != null) {
                    check(loopBConfigs.alternative == null) {
                        "Logic is broken, nullity of a & b are different, a is non-null $a, b is also non-null $b"
                    }
                    loopAConfigs.alternative to loopBConfigs.mainConfig
                } else {
                    check(loopBConfigs.alternative != null) {
                        "Logic is broken: nullity of a & b are different, a is non-null, but b is also non-null"
                    }
                    loopAConfigs.mainConfig to loopBConfigs.alternative
                }
            } else {
                loopAConfigs.mainConfig to loopBConfigs.mainConfig
            }

            val loopA = AnnotatedLoop(
                distinguishedExit = loopAConfigs.distinguishedExit,
                sourceLoop =  loopAConfigs.loop,
                head = aConfig.head,
                config = aConfig
            )

            val loopB = AnnotatedLoop(
                distinguishedExit = loopBConfigs.distinguishedExit,
                config = bConfig,
                head = bConfig.head,
                sourceLoop = loopBConfigs.loop,
            )

            val (loopAEq, loopBEq) = LoopEquivalence(
                queryContext = queryContext,
                ruleGenerator = ruleGeneration,
                loopA = LoopInProgram(loopA, program = a),
                loopB = LoopInProgram(loopB, program = b)
            ).proveInvariance() ?: return a to b
            return loopSummarizationIteration(
                ruleGeneration, queryContext, deleteLoop(a, loopAEq), deleteLoop(b, loopBEq)
            )
        }

        /**
         * Meta key for debugging purposes
         */
        private val inputPosition = MetaKey<Int>("loop.equiv.in-args")

        /**
         * Iteratively try summarizing the loops in [a] and [b], removing them when possible. The returned
         * pair has all equivalent loops replaced with sound summaries via [deleteLoop].
         */
        suspend fun <R: PureFunctionExtraction.CallingConvention<R>> trySummarizeLoops(
            ruleGeneration: AbstractRuleGeneration<R>,
            queryContext: EquivalenceQueryContext,
            a: CallableProgram<MethodMarker.METHODA, R>,
            b: CallableProgram<MethodMarker.METHODB, R>
        ) : Pair<CallableProgram<MethodMarker.METHODA, R>, CallableProgram<MethodMarker.METHODB, R>> {
            return loopSummarizationIteration(
                ruleGeneration, queryContext, a, b
            )
        }

        /**
         * Tracks the must write/may write status for variables and (static) memory locations.
         * Used to infer what locations are read before being written, and what locations are
         * written during the loop execution.
         */
        data class ReadWriteState(
            val mustWriteCells: TreapSet<BigInteger>,
            val mustWriteVars: TreapSet<TACSymbol.Var>,

            val mayWriteCells: TreapSet<BigInteger>,
            val mayWriteVars: TreapSet<TACSymbol.Var>
        ) {
            companion object {
                val empty = ReadWriteState(
                    treapSetOf(), treapSetOf(), treapSetOf(), treapSetOf()
                )
            }
        }

        /**
         * Find the "next" loop in the program, and extract the [LoopConfigOptions] representing
         * its effect. Returns null if no such loop exists, or extracting [LoopConfigOptions] didn't work.
         */
        private suspend fun extractPrimaryLoop(
            sourceProgram: CoreTACProgram
        ) : LoopConfigOptions? {
            logger.debug {
                "Starting loop scan in $sourceProgram"
            }
            val progLoops = getNaturalLoops(sourceProgram.analysisCache.graph)
            if(progLoops.any { l ->
                progLoops.any { other ->
                    other.head != l.head && l.head in other.body
                }
            }) {
                logger.debug { "have loop nesting, can't work in this situation" }
                return null
            }
            val sccGraph = SCCGraph(sourceProgram.blockgraph)
            // first loop in "program" order
            // XXX(jtoman): why on earth do we need to reverse this? I do not know!
            val ord = topologicalOrder(sccGraph.sccGraph).reversed()

            /**
             * Find the first strongly connected component in topological order that has size greater than 1:
             * this is the "first loop".
             */
            val firstLoop = ord.firstNotNullOfOrNull {
                sccGraph.sccs[it]?.takeIf { sccBody ->
                    sccBody.size > 1
                }
            } ?: return null
            val theLoop = progLoops.singleOrNull {
                it.head in firstLoop
            } ?: return null
            logger.debug {
                "Found first program loop with head ${theLoop.head}"
            }
            val g = sourceProgram.analysisCache.graph

            val head = theLoop.body.single { blk ->
                g.pred(blk).any { blkPred ->
                    blkPred !in theLoop.body
                }
            }
            check(head == theLoop.head) {
                "what am I doing???"
            }
            /**
             * Find the single successor block of the loop which is non-reverting; take
             * this to be our "exit".
             */
            val distinguishedTail = theLoop.body.flatMap {
                g.succ(it).filter {
                    it !in theLoop.body
                }
            }.filter {
                it !in g.cache.revertBlocks
            }.uniqueOrNull() ?: return null

            /**
             * Can we rotate the loop? That is, do we have a `while` loop whose condition on the first
             * iteration is always true? If so, find the head of the loop we'd use if we turned said `while` loop
             * into a `do/while` loop.
             */
            val altHead = canRotateLoop(
                theLoop = theLoop,
                coreTACProgram = sourceProgram
            )

            /**
             * Analyze the loop bodies in parallel, extracting the potential [LoopConfig]
             */
            return coroutineScope {
                val altConfigA = async {
                    altHead?.let {
                        extractLoopConfig(
                            sourceProgram, theLoop = theLoop, head = it, distinguishedTail = distinguishedTail
                        )
                    }
                }

                val mainConfigA = async {
                    extractLoopConfig(
                        sourceProgram, theLoop = theLoop, head = theLoop.head, distinguishedTail = distinguishedTail
                    )
                }

                val mainConfig = mainConfigA.await()
                val altConfig = altConfigA.await()
                val chosenMain = mainConfig ?: altConfig ?: return@coroutineScope null
                val chosenAlt = altConfig.takeIf {
                    chosenMain != altConfig
                }
                LoopConfigOptions(
                    theLoop,
                    distinguishedExit = distinguishedTail,
                    alternative = chosenAlt, mainConfig = chosenMain
                )
            }
        }

        /**
         * In [theLoop] within [core], starting from the effective head [head] and with distinguished exit [distinguishedTail],
         * collect the [LoopConfig]; recording the set of inputs, the writes collected during the loop, the invariant, etc.
         *
         * If this process fails (likely because the loop body contains a command we don't like) returns null
         */
        private fun extractLoopConfig(
            core: CoreTACProgram,
            theLoop: Loop,
            head: NBId,
            distinguishedTail: NBId
        ) : LoopConfig? {
            val g = core.analysisCache.graph
            val loopInputs = mutableSetOf<ProgramValueLocation>()

            val allWrites = mutableSetOf<ProgramValueLocation>()

            val writtenBeforeExit = treapSetBuilderOf<ProgramValueLocation>()

            val work = object : VisitingWorklistIteration<NBId, ProgramValueLocation, Boolean>() {
                val states = mutableMapOf<NBId, ReadWriteState>()

                override val scheduler: IWorklistScheduler<NBId>
                    get() = g.cache.naturalBlockScheduler

                override fun process(it: NBId): StepResult<NBId, ProgramValueLocation, Boolean> {
                    var st = states[it] ?: ReadWriteState.empty
                    val blk = g.elab(it)
                    val res = mutableListOf<ProgramValueLocation>()
                    for (lc in blk.commands) {
                        if (lc.cmd is TACCmd.Simple.AssigningCmd.ByteStore) {
                            /**
                             * Only support constant locations (like we see with vyper).
                             *
                             * We could **maybe** do something with a PTA here, but for now, nah
                             */
                            if (lc.cmd.loc !is TACSymbol.Const) {
                                return this.halt(false)
                            }
                            if (lc.cmd.value is TACSymbol.Var && lc.cmd.value !in st.mustWriteVars) {
                                res.add(ProgramValueLocation.StackVar(lc.cmd.value))
                            }
                            st = st.copy(
                                mustWriteCells = st.mustWriteCells + lc.cmd.loc.value,
                                mayWriteCells = st.mayWriteCells + lc.cmd.loc.value
                            )
                            allWrites.add(ProgramValueLocation.MemoryCell(lc.cmd.loc.value))
                        } else if (lc.cmd is TACCmd.Simple.AssigningCmd.ByteLoad) {
                            if (lc.cmd.loc !is TACSymbol.Const) {
                                return this.halt(false)
                            }
                            if (lc.cmd.loc.value !in st.mustWriteCells) {
                                res.add(ProgramValueLocation.MemoryCell(lc.cmd.loc.value))
                            }
                            st = st.copy(
                                mustWriteVars = st.mustWriteVars + lc.cmd.lhs,
                                mayWriteVars = st.mayWriteVars + lc.cmd.lhs
                            )
                            allWrites.add(ProgramValueLocation.StackVar(lc.cmd.lhs))
                        } else if (lc.cmd is TACCmd.Simple.LongAccesses && lc.cmd.accesses.any {
                                it.base == TACKeyword.MEMORY.toVar()
                            }) {
                            return this.halt(false)
                        } else {
                            for (v in lc.cmd.getFreeVarsOfRhs()) {
                                if (v !in st.mustWriteVars) {
                                    res.add(ProgramValueLocation.StackVar(v))
                                }
                            }
                            if (lc.cmd is TACCmd.Simple.AssigningCmd) {
                                st = st.copy(mustWriteVars = st.mustWriteVars + lc.cmd.lhs, mayWriteVars = st.mayWriteVars + lc.cmd.lhs)
                                allWrites.add(ProgramValueLocation.StackVar(lc.cmd.lhs))
                            }
                        }
                    }
                    val nxt = mutableListOf<NBId>()
                    for (succ in g.succ(it)) {
                        if(succ !in theLoop.body) {
                            if (succ == distinguishedTail) {
                                writtenBeforeExit.addAll(st.mayWriteCells.map { ProgramValueLocation.MemoryCell(it) })
                                writtenBeforeExit.addAll(st.mayWriteVars.retainAll { writeVar ->
                                    g.cache.lva.isLiveBefore(distinguishedTail, writeVar)
                                }.map {
                                    ProgramValueLocation.StackVar(it)
                                })
                            }
                            continue
                        }
                        states.merge(succ, st) { aV, bV ->
                            ReadWriteState(
                                mustWriteVars = aV.mustWriteVars intersect bV.mustWriteVars,
                                mustWriteCells = aV.mustWriteCells intersect bV.mustWriteCells,
                                mayWriteCells = aV.mayWriteCells union bV.mayWriteCells,
                                mayWriteVars = aV.mayWriteVars union bV.mayWriteVars
                            )
                        }
                        nxt.add(succ)
                    }
                    return StepResult.Ok(
                        next = nxt,
                        result = res
                    )
                }

                override fun reduce(results: List<ProgramValueLocation>): Boolean {
                    loopInputs.addAll(results)
                    return true
                }

            }
            val res = work.submit(listOf(head))
            if(!res) {
                return null
            }

            /**
             * Infer an invariant using the symbolic representation of
             * "one step" of the loop via [LoopConditionExtractor] and the initial values of loop variables.
             */
            val state = LoopConditionExtractor.analyzeLoop(
                core = core,
                theLoop = theLoop,
                head = head
            )
            val empty = LoopInvariant(
                inductivePC = setOf(),
                initial = treapMapOf(),
                inductiveState = treapMapOf()
            )
            val invariant = if(state != null) {
                val loopStart = CmdPointer(head, 0)
                /**
                 * Find those variables with a symbolic definition that have a constant definition at the start
                 * of the loop.
                 */
                val initialValuations = state.state.updateValues { k, _ ->
                    if(ProgramValueLocation.StackVar(k) !in loopInputs) {
                        return@updateValues null
                    }
                    constantDefBeforeLoop(
                        core.analysisCache.graph,
                        loop = theLoop,
                        where = loopStart,
                        v = k
                    )
                }

                /**
                 * Keep only those variables;
                 * this lets us express the value of those variables as a disjunction between "a constant" or symbolic term.
                 *
                 */
                val withInitial = state.state.retainAllKeys { k ->
                    k in initialValuations
                }

                /**
                 * Get all of the variables referenced in this definitions.
                 */
                val inductiveKeys = withInitial.flatMap { (k, e) ->
                    e.getFreeVars() + k
                }.toSet()

                /**
                 * Filter out the PC to only include these variables; this means we have a path condition
                 * expression which has some contraints on it via the withInitial definitions (and vice versa).
                 */
                val inductivePC = state.pc.filterToSet {
                    inductiveKeys.containsAll(it.getFreeVars())
                }
                if(inductivePC.isEmpty() || withInitial.isEmpty() || initialValuations.isEmpty()) {
                    empty
                } else {
                    LoopInvariant(
                        inductivePC = inductivePC,
                        initial = initialValuations,
                        inductiveState = withInitial
                    )
                }
            } else {
                empty
            }
            return LoopConfig(
                head = head,
                inputs = loopInputs,
                invariant = invariant,
                writesAtExit = writtenBeforeExit.build(),
                aliasAtExit = writtenBeforeExit.associateNotNull { k ->
                    if(k !is ProgramValueLocation.StackVar) {
                        return@associateNotNull null
                    }
                    val aliasAtEntrance = g.cache.gvn.findCopiesAt(
                        g.elab(theLoop.head).commands.first().ptr,
                        source = g.elab(distinguishedTail).commands.first().ptr to k.which
                    ).firstOrNull() ?: return@associateNotNull null
                    k.which to aliasAtEntrance
                },
                allWrites = allWrites
            )
        }

        /**
         * Determines whether the value of [v] at [where] has a singular definition outside of the [loop]
         * in [g] which is constant. If so, returns said constant, otherwise returns null.
         */
        private fun constantDefBeforeLoop(
            g: TACCommandGraph,
            v: TACSymbol.Var,
            where: CmdPointer,
            loop: Loop
        ) : BigInteger? {
            val strict = g.cache.strictDef
            val mca = g.cache[MustBeConstantAnalysis]
            fun getConstantDef(where: CmdPointer): BigInteger? = g.elab(where).maybeExpr<TACExpr.Sym>()?.let {
                mca.mustBeConstantAt(it.ptr, it.exp.s)
            }
            return strict.defSitesOf(
                v = v,
                pointer = where
            ).takeIf { defSites ->
                null !in defSites
            }?.singleOrNull { d ->
                d!!.block !in loop.body
            }?.let {
                getConstantDef(it)
            }
        }

        /**
         * Determines if the loop starts with a condition that must be true. If so,
         * this is a candidate for rotation, and the destination of this initially constant loop
         * can be the new head of the rotated loop. Said new head is returned, or null if
         * rotation is not possible.
         */
        private fun canRotateLoop(theLoop: Loop, coreTACProgram: CoreTACProgram) : NBId? {
            val g = coreTACProgram.analysisCache.graph
            val soloLoopDest = g.succ(theLoop.head).singleOrNull {
                it in theLoop.body
            } ?: return null
            val intoBody = g.pathConditionsOf(theLoop.head)[soloLoopDest]!!
            val initialBlock = g.elab(theLoop.head)
            val b = when(intoBody) {
                is PathCondition.EqZero -> {
                    DefiningEquationAnalysis.getDefiningEquation(
                        g = g,
                        v = intoBody.v,
                        where = initialBlock.commands.last().ptr,
                        target = initialBlock.commands.first().ptr
                    )?.let {
                        TACExpr.UnaryExp.LNot(it)
                    }
                }
                is PathCondition.NonZero -> {
                    DefiningEquationAnalysis.getDefiningEquation(
                        g = g,
                        v = intoBody.v,
                        where = initialBlock.commands.last().ptr,
                        target = initialBlock.commands.first().ptr
                    )
                }
                is PathCondition.Summary,
                PathCondition.TRUE -> return null
            } ?: return null
            if(initialBlock.commands.any {
                it.cmd !is TACCmd.Simple.AssigningCmd && it.cmd !is TACCmd.Simple.JumpiCmd
            }) {
                return null
            }
            if(initialBlock.commands.mapNotNull {
                it.cmd.getLhs()
            }.any {
                g.cache.lva.isLiveBefore(soloLoopDest, it)
            }) {
                return null
            }
            /**
             * Replace var references with the constant value they have
             * at the head of the loop (if such a constant exists)
             */
            val constantReplacer = object : DefaultTACExprTransformer() {
                override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
                    return constantDefBeforeLoop(
                        g, v = exp.s, loop = theLoop, where = initialBlock.commands.first().ptr
                    )?.asTACExpr ?: return exp
                }
            }
            return if(constantReplacer.transform(b).evalAsConst()?.let {
                it != BigInteger.ZERO
            } == true) {
                soloLoopDest
            } else {
                null
            }
        }

        /**
         * Instruments [p] so that control-flow does not reach a return/revert, and does not reach any loop besides that
         * with the head referenced in [p]'s [LoopInProgram.annotatedLoop]
         */
        private fun <M: MethodMarker, R: PureFunctionExtraction.CallingConvention<R>> instrumentNoReturns(
            p: LoopInProgram<M, R>,
        ): CallableProgram<M, R> {
            return p.program.map { core ->
                val truncated = core.getEndingBlocks().map {
                    val end = core.analysisCache.graph.elab(it).commands.last();
                    { p: SimplePatchingProgram ->
                        p.replaceCommand(end.ptr, listOf(
                            TACCmd.Simple.AssumeCmd(TACSymbol.False, "Function shouldn't return")
                        ))
                    }
                }.stream().patchForEach(core, check = true) { thunk ->
                    thunk(this)
                }
                getNaturalLoops(truncated.analysisCache.graph).filter {
                    it.head != p.annotatedLoop.sourceLoop.head
                }.stream().patchForEach(truncated, check = true) { l ->
                    if(!isBlockStillInGraph(l.head)) {
                        return@patchForEach
                    }
                    replaceLoopIn(
                        truncated,
                        this,
                        l,
                        CommandWithRequiredDecls(
                            TACCmd.Simple.AssumeCmd(TACSymbol.False, "not interested in this loop")
                        )
                    )
                }
            }
        }

        /**
         * Using [patcher], replace the loop [l] in [ctp] with the block of code [replacement]. Specifically,
         * all non-loop predecessors of the head of [l] will be rerouted to a new block containing [replacement].
         * Any code no longer reachable after this rerouting is dropped from the program.
         */
        private fun replaceLoopIn(
            ctp: CoreTACProgram,
            patcher: SimplePatchingProgram,
            l: Loop,
            replacement: CommandWithRequiredDecls<TACCmd.Simple>
        ) {
            replaceLoopIn(ctp, patcher, l.head, replacement)
        }

        /**
         * The same as the above, but using the explicit [head] block of the loop.
         */
        private fun replaceLoopIn(
            ctp: CoreTACProgram,
            patcher: SimplePatchingProgram,
            head: NBId,
            replacement: CommandWithRequiredDecls<TACCmd.Simple>
        ) {
            val dummy = patcher.addBlock(head,
                listOf(TACCmd.Simple.AssertCmd(TACSymbol.False, "removed"))
            )
            patcher.reroutePredecessorsTo(head, dummy) {
                ctp.analysisCache.domination.dominates(head, it)
            }
            val added = patcher.addBlock(head, replacement)
            patcher.consolidateEdges(added, listOf(head))
        }
    }

}
