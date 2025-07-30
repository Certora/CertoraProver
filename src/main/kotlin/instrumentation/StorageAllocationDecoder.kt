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

package instrumentation

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.PathCondition
import analysis.TACCommandGraph
import analysis.getNaturalLoops
import analysis.loop.AbstractArraySummaryExtractor
import analysis.loop.AbstractArraySummaryExtractor.Companion.simplify
import analysis.loop.LoopSummarization
import analysis.smtblaster.*
import config.Config
import evm.EVM_WORD_SIZE_INT
import parallel.Parallel
import parallel.ParallelPool
import parallel.Scheduler
import parallel.pcompute
import smtlibutils.data.SmtExp
import tac.NBId
import utils.*
import vc.data.AssigningSummary
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.getFreeVars
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import java.math.BigInteger

private const val symbolicLen = "LENGTH"
private const val symbolicStart = "START"

private val logger = Logger(LoggerTypes.ALLOC)

/**
 * A "worker class" to determine if code allocates an array out of storage,
 * according to the pattern in [analysis.alloc.StorageArrayAllocationAnalysis].
 * [end] is the `L` in that class' documentation, and [endVar] is the candidate `end` pointer.
 * All of this allocation occurs in the context of the [graph].
 *
 * At its heart, this analysis is quite stupid; it enumerates all paths start from the length
 * write until [end] (summarizing loops) and sees if on all such paths we must be setting
 * the [endVar] to an be a memory sequence large enough to hold an array. Quite BV heavy.
 */
class StorageAllocationDecoder private constructor(
    val graph: TACCommandGraph,
    val end: NBId,
    val endVar: TACSymbol.Var,
    val solvers: IBlaster
) {

    val loops = getNaturalLoops(graph)

    private val loopSummarizer = LoopSummarization(
        blaster = solvers,
        g = graph,
        loops = loops,
        includeFixups = false
    )

    companion object {
        operator fun invoke(
            graph: TACCommandGraph,

            start: CmdPointer,
            fpAlias: TACSymbol.Var,
            arrayLen: TACSymbol.Var,

            endVar: TACSymbol.Var,
            endLoc: NBId,

            solvers: IBlaster
        ) = StorageAllocationDecoder(
            graph = graph,
            end = endLoc,
            endVar = endVar,
            solvers = solvers
        ).doAnalysis(
            start, arrayLen, fpAlias
        )
    }

    /**
     * The incrementally built smt script encoding a path through the code. The [script] is the smt script
     * which holds the terms we are building. [variableMap] maps tacsymbols to freshly chosen smt identifiers;
     * these identifiers are uniquified with [varCounter]
     */
    private class BuilderState(
        val script: SmtExpScriptBuilder,
        val variableMap: MutableMap<TACSymbol.Var, String>,
        var varCounter: Int
    ) {
        /**
         * Get an identifier based on [v] that is unique in this smt script.
         * The identifier is *not* declared in the script.
         */
        fun fresh(v: String) = "${v}_${varCounter++}"

        val blaster = SmtExpBitBlaster()

        /**
         * Create a new binding for [l] in [variableMap] with no explicit definition. The identifier returned by this
         * function is *NOT* declared in the script, for that, see [declareFresh].
         */
        private fun bindFresh(l: TACSymbol.Var) : String {
            val nm = l.namePrefix + "_" + varCounter++
            variableMap[l] = nm
            return nm
        }

        /**
         * Create a new binding for [l], and then declare it as an unconstrained value, effectively havocing the variable.
         */
        fun declareFresh(l: TACSymbol.Var) {
            val ident = bindFresh(l)
            script.declare(ident)
        }

        /**
         * Create new binding for [l] in the script, defining its value to be equal to [e].
         */
        fun defineFresh(l: TACSymbol.Var, e: SmtExp) {
            script.define(bindFresh(l)) {
                e
            }
        }

        /**
         * Turn [e] into an equivalent [SmtExp], using [variableMap] to get the "current" names for variables
         * mentioned in [e]. Returns null if the blasting fails due to, e.g., unsupported expression types,
         * missing variable bindings, etc.
         */
        fun blastExpr(e: TACExpr) : SmtExp? {
            return blaster.blastExpr(e = e, variableMap::get)
        }

        /**
         * Copy this object, used at control flow forks or to make subqueries
         */
        fun fork() = BuilderState(
            script = script.fork(),
            variableMap = variableMap.toMap().toMutableMap(),
            varCounter = varCounter
        )
    }

    /**
     * The start of the analysis. [start] is the location where the length is written into memory at location
     * [fpAlias] with the value [arrayLen]. These variables are taken to alias the special identifiers [symbolicLen]
     * and [symbolicStart], which represent "the length of the array" and "the start of the array" repsectively.
     */
    private fun doAnalysis(
        start: CmdPointer,
        arrayLen: TACSymbol.Var,
        fpAlias: TACSymbol.Var
    ) : BigInteger? {
        val state = BuilderState(
            varCounter = 0,
            variableMap = mutableMapOf(
                arrayLen to symbolicLen,
                fpAlias to symbolicStart
            ),
            script = SmtExpScriptBuilder(
                termBuilder = SmtExpBitVectorTermBuilder
            )
        )
        state.script.declare(symbolicLen)
        state.script.declare(symbolicStart)
        val res = when(val work = handleBlock(state, start.toLeft(), depth = 0)) {
            is Either.Right -> {
                logger.info {
                    "Could not interpret alloc at $end targeting $endVar: ${work.d}"
                }
                return null
            }
            is Either.Left -> {
                ParallelPool.inherit(spawnPolicy = ParallelPool.SpawnPolicy.GLOBAL) { pool ->
                    pool.run(work.d)
                }
            }
        }
        return when(res) {
            is Either.Left -> res.d
            is Either.Right -> {
                // uh.. the whole thing was unreachable? say no size I guess
                null
            }
        }
    }

    /**
     * Marker object for "impossible path". We can't use null, becuase THAT means "failed match".
     */
    data object INFEASIBLE

    /**
     * In the [state], starting from either a [CmdPointer] or a [NBId], try to infer the sizes.
     *
     * The return signature is, uh, definitely one of the types of all time.
     *
     * Let's break it down:
     * Either<
     *    // The function either succeeds with parallel job, that computes:
     *    Parallel<
     *       // either ...
     *       Either<
     *           // the size of the elements, or null if this code doesn't allocate an array, OR
     *           BigInteger?,
     *           // that the path was infeasible
     *           INFEASIBLE
     *      > // end either
     *   > // end parallel, OR
     *   // The function fails with a string describing why the analysis failed
     *   String
     * >
     */
    private fun handleBlock(
        state: BuilderState,
        seed: Either<CmdPointer, NBId>,
        depth: Int,
    ) : Either<Parallel<Either<BigInteger?, INFEASIBLE>>, String> {
        if(depth >= Config.MaxStorageAllocationAnalysisDepth.get()) {
            return "Depth exceeded at $seed".toRight()
        }
        val seq = seed.toValue({
            graph.iterateBlock(it, excludeStart = false)
        }, {
            graph.elab(it).commands
        })
        for(l in seq) {
            if(!stepCommand(l, state)) {
                return "Failed stepping command $l".toRight()
            }
        }
        val currBlock = seed.toValue(CmdPointer::block) { it }
        val succ = graph.succ(currBlock)
        /**
         * If our [end] is in our successors, stop tracing forward, and check if we are allocating an array,
         * and what size it must be
         */
        if(end in succ) {
            /*
             * Don't allow for conditional completion.
             *
             * I expect to revisit this in a year to carve out an exception for revert blocks
             */
            if(succ.size != 1) {
                return "Other successors from $currBlock when going to $end".toRight()
            }
            return processEnd(state, currBlock)
        }
        /**
         * Otherwise we have a branch. Fork the state.
         */
        if(succ.size != 1) {
            val pc = graph.pathConditionsOf(currBlock)
            return succ.map { nxt ->
                val withPc = state.fork()
                /**
                 * If we have a non-trivial path condition, add that to our constraints
                 */
                when(val path = pc[nxt]) {
                    is PathCondition.EqZero -> {
                        val curr = state.variableMap[path.v]
                        if(curr != null) {
                            withPc.script.assert {
                                eq(
                                    toIdent(curr),
                                    const(0)
                                )
                            }
                        }
                    }
                    is PathCondition.NonZero -> {
                        val curr = state.variableMap[path.v]
                        if(curr != null) {
                            withPc.script.assert {
                                lnot(
                                    eq(toIdent(curr), const(0))
                                )
                            }
                        }
                    }
                    is PathCondition.Summary -> {
                        return "Did not expect to see a block with a summary ${path.s.annotationDesc} at $seed".toRight()
                    }
                    PathCondition.TRUE,
                    null -> { }
                }
                /**
                 * In what follows, we explicitly check for infeasible paths and prune our search early.
                 * This may seem strange, because at first glance you'd think there shouldn't
                 * be any infeasible paths in code with unknown lengths.
                 *
                 * However, there are two reasons why we do this, and they are both due to how packed storage arrays are copied.
                 * Suppose you are copying `uint64[]` which has 4 per storage slot.
                 * Solidity copies from storage until there are fewer than 4 elements left. Let `elemCounter` be
                 * the number of elements copied so far. It then generates this:
                 * ```
                 * if(elemCounter < length) {
                 *    // copy another element
                 *    elemCounter++
                 * }
                 * if(elemCounter < length) {
                 *    // ...
                 *    elemCounter++
                 * }
                 * if(elemCounter < length) {
                 *    // ...
                 *    elemCounter++
                 * }
                 * if(elemCounter < length) {
                 *    // ...
                 *    elemCounter++
                 * }
                 * ```
                 * This shows both reasons. The first: that final `if(elemCounter < length)` check is *always* false.
                 * If we use `unsat` to infer what size elements we are looking at, and we have an always infeasible static path,
                 * then we will get spurious failures because given we're effectively assuming false, element size could
                 * be 1 and 32 at the same time, why not?
                 *
                 * The other problem is with the structure here. If the first condition is false, all the remaining
                 * conditions will be false, so we have to prune the paths where we try to enter the later conditionals
                 * we'll get a statically infeasible path again.
                 *
                 * As an aside, if we enumerate all paths, we get 16 possible paths, of which only *4*
                 * are possible (for 0 remaining elems, 1 remaining elem, etc.) so there are performance reasons to prune
                 * early (although we need to actually generate sub problems).
                 *
                 * For these reasons, we always check if the path condition is infeasible. To do this, we simply fork
                 * the script, and check satisfiability. If we get unsat, the path conditions (and program definitions)
                 * are unsatisfiable, and thus the path is infeasible.
                 */
                val checkPc = withPc.script.fork()
                checkPc.checkSat()
                /**
                 * Because of the signature of the function, we generate the parallel work for the successor blocks
                 * even if they are infeasible. This is fine, because the construction of these parallels is relative cheap;
                 * running them is expective. The parallel returned from this recursive call is not run until we've
                 * run the path condition check and verified that the path is feasible.
                 *
                 * Note that if we have some fatal error in a block that is infeasible, we'll end up tanking the whole process
                 * pessimistically. This is an acceptable risk compared to making an even more gnarly return signature.
                 */
                handleBlock(withPc, nxt.toRight(), depth + 1).mapLeft { p ->
                    Scheduler.rpc {
                        solvers.blastSmt(checkPc.cmdList)
                    }.bind { infeasible ->
                        /**
                         * [IBlaster.blastSmt] returns true if the query was unsat, which in this
                         * case means the path is infeasible, so return that.
                         */
                        if(infeasible) {
                            complete(INFEASIBLE.toRight())
                        } else {
                            /**
                             * Remember the parallel returned from bind is then executed, so only now do we
                             * execute all of the work.
                             */
                            p
                        }
                    }
                }
            }.flattenLeft().bindLeft { jobList ->
                /**
                 * If all the success blocks were turned into a parallel, run them in parallel
                 */
                jobList.pcompute().map { jobRes ->
                    /**
                     * If all path are infeasible, then this path is also infeasible
                     */
                    if(jobRes.all { it is Either.Right }) {
                        return@map INFEASIBLE.toRight()
                    }
                    /**
                     * At least one feasible path, grab the results, merge them to a single unique value (the size)
                     * or null.
                     */
                    jobRes.mapNotNull {
                        it as? Either.Left
                    }.monadicMap {
                        it.d
                    }?.uniqueOrNull().toLeft()
                }.toLeft()
            }
        }
        /**
         * Otherwise a single successor. Is this a loop head?
         */
        val soleSucc = succ.single()
        val loops = loops.filter { l ->
            l.head == soleSucc
        }
        /**
         * No? then just continue on straight line code
         */
        if(loops.isEmpty()) {
            return handleBlock(state, soleSucc.toRight(), depth + 1)
        }
        /**
         * Multiple loops -> bad time, bail out
         */
        if(loops.size != 1) {
            return "Multiple loops with header $soleSucc".toRight()
        }
        val principle = loops.single()

        /**
         * Find the successor loop.
         */
        val skipTarget = principle.body.flatMap { b ->
            graph.succ(b)
        }.singleOrNull { lSucc ->
            lSucc !in principle.body
        } ?: return "could not find single loop exit from $principle".toRight()
        if(skipTarget == end) {
            return "No handling for end block".toRight()
        }

        val summ = loopSummarizer.summarizeLoop(principle) ?: return "Failed to summarize loop $soleSucc from $currBlock".toRight()

        val exitCondVars = summ.exitCondition.getFreeVars()

        /**
         * For each variable v found in the loop condition that are mutated within a loop body,
         * generate a function `f(ident)`. `f(i)` generates an SMT term describing the value
         * of v after `i` iterations. Because the only type of mutation we support is addition by some constant `k`,
         * `f(ident) = curr(v) + i * k`. Where `curr(v)` is the identifier giving the current value of `v`.
         *
         * If any of these steps fail (there is an unsupported type of mutation, `v` isn't bound, etc.)
         * the entire analysis terminates with an error message.
         *
         */
        val interp = AbstractArraySummaryExtractor.interpolateLoopVars(summ) { cand ->
            cand in exitCondVars
        } ?: return "Could not interpolate variables for exit condition ${summ.exitCondition} in $soleSucc".toRight()

        /**
         * Thus, `loopEff[v](i)` gives an smt term that gives `v`'s value after `i` iterations.
         */
        val loopEff = exitCondVars.mapNotNull {
            it `to?` interp[it]
        }.filter { (_, exp) ->
            exp !is AbstractArraySummaryExtractor.Interpolation.Identity
        }.monadicMap { (v, l) ->
            val ident = state.variableMap[v] ?: return@monadicMap null
            (v to ident) `to?` (l as? AbstractArraySummaryExtractor.Interpolation.Linear)
        }?.associate { (id, eff) ->
            val (v, name) = id
            v to { ind: SmtExp ->
                with(state.script) {
                    plus(
                        toIdent(name),
                        mult(ind, const(eff.n))
                    )
                }
            }
        } ?: return "Could not interpolate other variables for loop $soleSucc".toRight()
        val finalIter = state.fresh("finalIter")
        val prevIter = state.fresh("prev")
        state.script.declare(finalIter)
        state.script.declare(prevIter)
        /**
         * Use the usual trick to infer how many iterations were run. Define `finalIter = prevIter + 1`,
         * and assert that the exit condition was *false* after prevIter iterations, but *true* at finalIter.
         * This assumes a monotonicity of loop exits that I'm perfectly fine with.
         *
         * We do this by frist generating, for all mutated variables v in the exitCondition, `v@prevIter` which
         * is defined to be `loopEff[v](prevIter)`, and similarly `v@finalIter` using `finalIter`.
         */
        val prevIterIncarn = loopEff.mapValues { (k, gen) ->
            val prevName = state.fresh(k.namePrefix)
            state.script.define(prevName) {
                gen(toIdent(prevIter))
            }
            prevName
        }
        val finalIncarn = loopEff.mapValues { (k, gen) ->
            val finalName = state.fresh(k.namePrefix)
            state.script.define(finalName) {
                gen(toIdent(finalIter))
            }
            finalName
        }

        /**
         * We then generate `exitCondition@prevIter` (aka prevBlast) by simply doing `exitCondition[v@prevIter / v]` for all such `v`
         * and similarly for `exitCondition@finalIter` (aka finalBlast).
         */
        val prevBlast = state.blaster.blastExpr(summ.exitCondition) {
            prevIterIncarn[it] ?: state.variableMap[it]
        } ?: return "Could not blast exit condition prior last iteration ${summ.exitCondition}".toRight()
        val finalBlast = state.blaster.blastExpr(summ.exitCondition) {
            finalIncarn[it] ?: state.variableMap[it]
        } ?: return "Could not blast exit condition post-loop execution ${summ.exitCondition}".toRight()

        /**
         * the exit condition before any iterations
         */
        val currEval = state.blastExpr(summ.exitCondition) ?: return "could not translate exit condition ${summ.exitCondition}".toRight()

        /**
         * But where is `finalIter = prevIter + 1`? We also need to consider the case where there are *no* iterations,
         * i.e. where currEval is already true. In that case, we don't *really* want to insist that prevIter = MAX_UINT256
         * and hope that that just "works out", so we use a disjunction. See below
         */
        state.script.assert {
            lor(
                /**
                 * We assert that either there are no iterations (currEval is true), and finalIter must be 0
                 */
                // no iterations
                land(
                    eq(currEval, const(1)),
                    eq(toIdent(finalIter), const(0))
                ),
                // at least one iteration
                /**
                 * Or currEval is false (that is, the exit condition is false and the loop must execute once)
                 * in which case finalIter must be prevIter + 1, where prevBlast and finalBlast (aka exitCondition@prevIter
                 * and exitCondition@finalIter) are constrained as described above.
                 */
                land(
                    eq(currEval, const(0)),
                    eq(toIdent(finalIter), plus(toIdent(prevIter), const(1))),
                    eq(prevBlast, const(0)),
                    eq(finalBlast, const(1))
                )
            )
        }
        /**
         * Finally, for all of the variables mutated in the loop (not just those in the exist condition)
         * mutate them according to the number of iterations. We can be pretty lazy with these approximations,
         * just havocing if need be.
         */
        for((v, e) in summ.iterationEffect) {
            // identity effect, move on
            if(e is TACExpr.Sym.Var && e.s == v) {
                continue
            }
            if(e is TACExpr.Sym.Const) {
                state.defineFresh(v, state.script.const(e.s.value))
                continue
            } else if(e !is TACExpr.Vec.Add) {
                // just havoc
                state.declareFresh(v)
                continue
            }
            val c = e.simplify().let {
                AbstractArraySummaryExtractor.extractConstantOperand(it.ls, v)
            }
            val currValue = state.variableMap[v]
            /**
             * We either aren't adding by a constant, or we don't know about the initial value. Either way, just havoc.
             */
            if(c == null || currValue == null) {
                state.declareFresh(v)
                continue
            }
            /**
             * Otherwise, define the new value of `k` to be `k@curr + finalIter * c`
             */
            state.defineFresh(v, with(state.script) {
                plus(
                    toIdent(currValue),
                    mult(
                        toIdent(finalIter),
                        const(c)
                    )
                )
            })
        }
        if(skipTarget == end) {
            return processEnd(state, currBlock)
        }
        // phew!
        return handleBlock(state, skipTarget.toRight(), depth + 1)
    }

    /**
     * Called to handle when we reach the [end] block from [currBlock]. Actually sets up the SMT query to check
     * the element block sizes.
     */
    private fun processEnd(
        state: BuilderState,
        currBlock: NBId
    ): Either<Parallel<Either<BigInteger?, Nothing>>, String> {
        /**
         * Did we infer a value for the [endVar]? If it is "undefined" along this path, halt now.
         */
        val t = state.variableMap[endVar] ?: return "No value found for $endVar going from $currBlock -> $end".toRight()

        /**
         * The size of the element blocks for the array, i.e., the size of the allocated array segment minus the 32 bytes
         * for the length.
         */
        val blockSizeId = state.fresh("blockSize")
        state.script.define(blockSizeId) {
            minus(
                minus(
                    toIdent(t),
                    toIdent(symbolicStart)
                ),
                const(EVM_WORD_SIZE_INT)
            )
        }

        // two possiblities, packed byte array or word array
        val byteArray = state.script.fork().run {
            /**
             * Then, we need to see if the block is at least as large as [symbolicLen] elements,
             * but is *no more* than 31 words past the "exact" length. Why not insist on exact lengths? Well, when
             * copying bytes (and strings) from storage, the compiler will work in 32 byte sized blocks, so unless the
             * length of the array is divisible by 32, we won't always have an exact match, but we can insist on seeing
             * the "closest" match.
             *
             * Remember that the allocation we're analyzing is defined as `roundup([endVar] - fp) + fp`. We have an
             * SMT term `t` for the value of [endVar] which is an expression over [symbolicLen], [symbolicStart], and others. Further, we defined
             * [symbolicStart] to be the value of fp during the allocation. Then, we can calculate the length of the
             * array block (in terms of physical bytes as) `t - symbolicStart`. If this size is exactly [symbolicLen],
             * then we have a bytes array allocation.
             *
             * However, if this is a bytes (or string) array, then solidity actually "over copies" so the size of this block will always
             * be [symbolicLen] rounded up to the nearest multiple of the word size (which, yes, makes the explicit roundup that solidity does redundant).
             * Thus, when we query the SMT solver to see if this block size matches, we generate that the following formula must be true:
             * `t - symbolicStart >= symbolicLen && (t - symbolicStart - symbolicLen) < 32`
             * That is, the size of the elements block is large enough to fit the array, but the excess
             * is bounded to be less than a word.
             */
            assert {
                val symbolicLenExp = toIdent(symbolicLen)
                val blockLen = toIdent(blockSizeId)
                lnot(
                    land(
                        ge(
                            blockLen,
                            symbolicLenExp
                        ),
                        lt(
                            minus(blockLen, symbolicLenExp), const(EVM_WORD_SIZE_INT)
                        )
                    )
                )
            }
            checkSat()
            Scheduler.rpc {
                val vc_query = cmdList
                if (solvers.blastSmt(vc_query)) {
                    BigInteger.ONE
                } else {
                    null
                }
            }
        }
        val wordArray = state.script.fork().run {
            /**
             * In this case, insist on exact matches, the element block size must equal
             * the length * 32. That is, we check if the following must be true:
             * `t - symbolicStart == symbolicLen * 32`
             *
             * Of course, we construct this as asserting this can't be the case, and expect unsat.
             */
            assert {
                val blockLen = toIdent(blockSizeId)
                val symbolicLen = toIdent(symbolicLen)
                lnot(
                    eq(
                        blockLen,
                        mult(symbolicLen, const(EVM_WORD_SIZE))
                    )
                )
            }
            checkSat()
            Scheduler.rpc {
                val vc_query = cmdList
                if (solvers.blastSmt(vc_query)) {
                    EVM_WORD_SIZE
                } else {
                    null
                }
            }
        }
        return byteArray.parallelBind(wordArray) { sz1, sz2 ->
            /**
             * If both are non-null, we fail immediately, this is ambiguous.
             */
            if (sz1 != null && sz2 != null) {
                complete(null.toLeft())
            } else {
                complete((sz1 ?: sz2).toLeft())
            }
        }.toLeft()
    }

    /**
     * Basic lifting of tac commands to scalars. Havoc everything that isn't a [vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd],
     * and havoc those commands where we couldn't blast the rhs.
     *
     * Returns false if we hit something unexpected (end of function, call, assert, etc.)
     */
    private fun stepCommand(l: LTACCmd, state: BuilderState) : Boolean {
        when(l.cmd) {
            is TACCmd.Simple.AssigningCmd -> {
                if(l.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    state.declareFresh(l.cmd.lhs)
                    return true
                }
                val exp = state.blastExpr(l.cmd.rhs)
                if(exp == null) {
                    state.declareFresh(l.cmd.lhs)
                    return true
                }
                state.defineFresh(l.cmd.lhs, exp)
            }
            is TACCmd.Simple.Assume -> {
                val gen : (SmtExp) -> SmtExp = if(l.cmd is TACCmd.Simple.AssumeCmd) {
                    { e -> state.script.lnot(e) }
                } else {
                    { e -> e }
                }
                val exp = state.blastExpr(l.cmd.condExpr) ?: return true
                state.script.assert {
                    gen(eq(exp, const(0)))
                }
            }

            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.ReturnSymCmd,
            is TACCmd.Simple.RevertCmd,
            is TACCmd.Simple.AssertCmd,
            is TACCmd.Simple.CallCore,
            is TACCmd.Simple.LogCmd -> {
                return false
            }
            is TACCmd.Simple.AnnotationCmd,
            is TACCmd.Simple.ByteLongCopy,
            is TACCmd.Simple.JumpCmd,
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.JumpiCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.WordStore,
            TACCmd.Simple.NopCmd -> {
                // intentionally left blank
                // no effect on scalar values
                return true
            }
            is TACCmd.Simple.SummaryCmd -> {
                if(l.cmd.summ is AssigningSummary) {
                    l.cmd.summ.mayWriteVars.forEach {
                        state.declareFresh(it)
                    }
                }
            }
        }
        return true
    }
}
