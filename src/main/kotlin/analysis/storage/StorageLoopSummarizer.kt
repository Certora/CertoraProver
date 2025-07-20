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

package analysis.storage

import analysis.*
import analysis.loop.AbstractArraySummaryExtractor
import analysis.loop.LoopSummarization
import analysis.loop.LoopSummarization.LoopIterationSummary
import analysis.numeric.IntValue
import analysis.smtblaster.*
import com.certora.collect.*
import datastructures.stdcollections.*
import parallel.*
import smt.TacExpTermsOfInterest
import smtlibutils.cmdprocessor.SmtFormula
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.data.FactorySmtScript
import smtlibutils.data.SatResult
import solver.toTacAssignment
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.*
import java.math.BigInteger

object StorageLoopSummarizer {
    private val testFixupLoop: PatternMatcher.Pattern<TACSymbol.Var> = PatternDSL.build {
        (Var `==` 0()).commute.first
    }

    private data class CandidateLoopInfo(
        val storageCmd: List<CmdPointer>,
        val succ: NBId,
    )

    private class Worker(val graph: TACCommandGraph) {

        val nontriv = NonTrivialDefAnalysis(graph)
        val mustBeConstant = MustBeConstantAnalysis(graph, nontriv)
        val fixupLoopPattern = PatternMatcher.compilePattern(graph, testFixupLoop)

        /**
         * @return a non-null value if [l] is a loop we can/want to summarize:
         * - it only has a header/body
         * - it has a storage command
         * - there is a single successor of the loop (besides the loop blocks themselves)
         */
        private fun storageToMemoryLoopCandidate(graph: TACCommandGraph, l: Loop): CandidateLoopInfo? {
            if (l.body != setOf(l.head, l.tail)) {
                return null
            }

            val copyCmds = l.body.asSequence().flatMap { graph.elab(it).commands }.filter {
                it.cmd is TACCmd.Simple.StorageAccessCmd && it.cmd.base.isStorageOrTransientStorage()
            }.map {
                it.ptr
            }.toList().takeIf {
                it.isNotEmpty()
            } ?: return null

            val succ = (graph.succ(l.head) - l.body).uniqueOrNull() ?: return null

            return CandidateLoopInfo(copyCmds, succ)
        }


        fun TACExpr.substVar(from: TACSymbol.Var, to: TACExpr): TACExpr =
            TACExprUtils.SubstitutorVar(mapOf(from.asSym() to to)).transform(this)

        /**
         * Determine the initial value of [x] before entering the loop summarized by [loopSummary]
         * @return if not null: (ptr, s) [x] is initially assigned `s` at [ptr]. Note if `[x]` is initially assigned
         *         the value of some variable `y` at `s`, and `y` must be constant at s, then this constant is returned.
         */
        private fun initialValue(x: TACSymbol.Var, loopSummary: LoopIterationSummary): Pair<CmdPointer, TACSymbol>? {
            val initialCmd = graph.elab(loopSummary.loop.head).commands.first()

            val (x0DefSite, x0) = graph.cache.def.defSitesOf(x, initialCmd.ptr).singleOrNull {
                it.block !in loopSummary.loop.body
            }?.takeIf { defSite ->
                graph.cache.domination.dominates(defSite.block, loopSummary.loop.head)
            }?.let { defSite ->
                defSite `to?` (graph.elab(defSite)
                    .maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs as? TACExpr.Sym)?.s
            } ?: return null

            return (x0 as? TACSymbol.Var)?.let { x0v ->
                x0DefSite `to?` mustBeConstant.mustBeConstantAt(
                    initialCmd.ptr, x0v
                )?.asTACSymbol()
            } ?: (x0DefSite to x0)
        }

        /**
         * Returns the unique value of [s] for all models satisfying [constraint] (if it exists).
         * [constraint] must be a [TACExpr.BoolExp]
         */
        private fun getUniqueSolution(
            s: TACSymbol.Var,
            constraint: TACExpr,
            blaster: SmtExpIntBlaster,
        ): BigInteger? {
            check(constraint is TACExpr.BoolExp)

            val fvs = TACExprFreeVarsCollector.getFreeVars(constraint)
            val smtCommands = SmtExpScriptBuilder(SmtExpIntTermBuilder)
            val smtConstraint = blaster.blastExpr(constraint) {
                it.toSMTRep()
            } ?: return null

            fvs.forEach {
                smtCommands.declare(it.toSMTRep())
            }
            smtCommands.assert {
                eq(const(1), smtConstraint)
            }

            // Forking here because once we get a model (i.e. a value V for N), we're going to
            // add a constraint (V != N) and check for UNSAT
            val formula = SmtFormula.fromSmt(FactorySmtScript.smt(smtCommands.fork().cmdList))

            val numIterationsExp = SmtExpIntTermBuilder.script.qualIdentifier(
                SmtExpIntTermBuilder.script.simpleIdentifier(s.toSMTRep()),
                null,
                SmtExpIntTermBuilder.numericType
            )

            val result = Z3Blaster.querySmt(
                formula,
                TacExpTermsOfInterest(setOf(numIterationsExp), mapOf()) { smtExp ->
                    s.takeIf { smtExp == numIterationsExp }?.asSym()
                }
            )

            return if (result is SmtFormulaCheckerResult.Single.Simple && result.satResult is SatResult.SAT) {
                result
                    .toTacAssignment()[s]
                    ?.asBigIntOrNull()
                    ?.takeIf {
                        BigInteger.ZERO <= it
                            && run {
                            // We got the model `N = it`. We only return this if it's the
                            // ONLY solution. So we add `N != it` to our constraints
                            // If `blastSmt` returns `true` then the constraints are UNSAT,
                            // i.e. `it` is the only solution
                            smtCommands.assert { lnot(eq(numIterationsExp, const(it))) }
                            smtCommands.checkSat()
                            Z3Blaster.blastSmt(smtCommands.cmdList)
                        }
                    }
            } else {
                null
            }
        }


        /* Inline definitions in cond by backwards reasoning */
        private fun liftExpr(target: NBId, expr: TACExpr): TACExpr {
            val originalFreeVariables = expr.getFreeVars()
            var liftedExpr = expr
            var nextTarget: NBId = target
            while (liftedExpr.getFreeVars().containsAny(originalFreeVariables)) {
                // Get the defEq of the condition (assumed to hold at the last command of [target])
                // in terms of the vocabulary at the first command of [target],
                // otherwise bail
                val sub = liftedExpr.getFreeVars().monadicMap {
                    it.asSym() `to?` DefiningEquationAnalysis.getDefiningEquation(
                        graph, it, graph.elab(nextTarget).commands.last().ptr, CmdPointer(nextTarget, 0)
                    )
                }?.toMap() ?: break

                liftedExpr = TACExprUtils.SubstitutorVar(sub).transform(liftedExpr)
                nextTarget = graph.pred(nextTarget).singleOrNull() ?: break
            }
            return liftedExpr
        }

        /**
         * Try and calculate the number of iterations executed by the summarized loop.
         */
        private fun numberOfIterations(
            jump: LTACCmd,
            summary: LoopIterationSummary,
            blaster: SmtExpIntBlaster,
        ): BigInteger? {
            val condVar = jump.maybeNarrow<TACCmd.Simple.JumpiCmd>()?.cmd?.cond as? TACSymbol.Var ?: return null

            val loopHead = CmdPointer(summary.loop.head, 0)
            val loopCondition = DefiningEquationAnalysis.getDefiningEquation(
                graph,
                condVar,
                jump.ptr,
                loopHead,
            )?.let {
                if (jump.narrow<TACCmd.Simple.JumpiCmd>().cmd.dst in summary.loop.body) {
                    it
                } else {
                    TACExpr.UnaryExp.LNot(it)
                }
            } ?: return null

            /* Assuming we have something like
               ...
               y = ...
               x = ...
               i0 = f(x, y)
               L: i = i0
               ...
               while (cond) {
                 i += k
               }

               [guessedInductionVar] is i
               [initLoc] is L
               [inductionInit] is i0
             */
            val nonIdentity = summary.nonIdentityIterationEffects()
            val guessedInductionVar = TACExprFreeVarsCollector.getFreeVars(loopCondition).singleOrNull {
                it in nonIdentity
            } ?: return null

            // Finally we need to get the effect of guessedInductionVar for each iteration.
            val increment = AbstractArraySummaryExtractor
                .interpolateLoopVars(summary) {
                    it == guessedInductionVar
                }?.get(guessedInductionVar)?.let {
                    it as? AbstractArraySummaryExtractor.Interpolation.Linear
                }?.n ?: return null

            // We're going to ask the solver for a unique model for N, that will
            // be our number of iterations
            val numIterations = TACSymbol.Var("N", Tag.Bit256)

            // If N is the number of iterations, then the loop condition succeeds for the last
            // time on N-1
            //
            // This is true for do-loops as well as "normal" loops, because we're effectively
            // counting the number of times we enter the loop header block such that the backedge
            // conditional jump will succeed.
            val nInFinalExecution =
                TACExpr.BinOp.Sub(numIterations.asSym(), BigInteger.ONE.asTACExpr, Tag.Bit256)

            val finalValue = TACExpr.Vec.Add(
                guessedInductionVar.asSym(),
                TACExpr.Vec.Mul(nInFinalExecution, increment.asTACExpr, Tag.Bit256),
                Tag.Bit256
            )

            /* We're building
                 PreExitCondition(N) = 1 && PostExitCondition(N) = 0
               where these formulas are the loop condition with our induction variable
               replaced by its (symbolic) value in terms of the initial value and
               linear effect
             */
            val exitValue = TACExpr.Vec.Add(finalValue, increment.asTACExpr, Tag.Bit256)
            // express any other free variables in terms of vars defined at the beginning of the
            // loop block
            val preExitCondition = loopCondition.substVar(guessedInductionVar, finalValue)
            val postExitCondition = loopCondition.substVar(guessedInductionVar, exitValue)

            // Propagate this backward to inline as many definitions as possible
            val constraintAtLoopHeader = TACExpr.BinBoolOp.LAnd(
                preExitCondition,
                TACExpr.UnaryExp.LNot(postExitCondition, Tag.Bool), Tag.Bool
            )
            val constraint = graph.pred(summary.loop.head)
                .singleOrNull() { it !in summary.loop.body }
                ?.let { pred ->
                    liftExpr(pred, constraintAtLoopHeader)
                } ?: constraintAtLoopHeader

            val constraintFvs = TACExprFreeVarsCollector.getFreeVars(constraint)
            if (constraintFvs.any { it != guessedInductionVar && it in nonIdentity }) {
                return null
            }

            val fvsGtZero = constraintFvs.filter { it != numIterations }
                .fold(TACSymbol.True.asSym()) { e: TACExpr, v ->
                    TACExpr.BinBoolOp.LAnd(
                        e, TACExpr.BinRel.Le(TACExpr.zeroExpr, v.asSym())
                    )
                }

            return getUniqueSolution(
                numIterations,
                TACExpr.BinBoolOp.LAnd(constraint, fvsGtZero),
                blaster
            )?.letIf(summary.isDoLoop) {
                it + BigInteger.ONE
            }
        }

        /**
         * Generates ((y + 2*k - 1)/32) + x
         */
        private fun packedSlotUpdate(x: TACSymbol.Var, y: TACSymbol.Var, k: TACSymbol.Const): TACExpr {
            val slotsUsed = (2.toBigInteger() * k.value) - 1.toBigInteger()
            val nextLastByte = TACExpr.Vec.Add(y.asSym(), slotsUsed.asTACSymbol().asSym())
            val nextWord = TACExpr.BinOp.Div(nextLastByte, 32.asTACSymbol().asSym())
            return TACExpr.Vec.Add(nextWord, x.asSym())
        }

        /**
         * Generates (1 - ((x + 2*k - 1)/32))*(x + k)
         *
         * If x is a byte offset in a word, then this expression is the next byte offset
         * to use: we add 2*k-1 to account for the additional bytes needed for the value starting at x,
         * followed by the bytes needed for the subsequent value. If we need more bytes than are remaining
         * (i.e. if x + 2*k - 1 exceeds 31), then we move on to offset 0 (of the next word)
         */
        private fun byteOffsetUpdate(x: TACSymbol.Var, k: TACSymbol.Const): TACExpr {
            // let nextByte = x + k
            // let nextLastByte = nextByte + k-1
            // let nextNextOverflowsWord = nextLastByte / 32
            // let x' = (1 - nextNextOverflowsWord) * nextByte
            val slotsUsed = (2.toBigInteger() * k.value) - 1.toBigInteger()
            val nextLastByte = TACExpr.Vec.Add(x.asSym(), slotsUsed.asTACSymbol().asSym())
            val nextWord = TACExpr.BinOp.Div(nextLastByte, 32.asTACSymbol().asSym())
            val overflowMask = TACExpr.BinOp.Sub(1.asTACSymbol().asSym(), nextWord)

            return TACExpr.Vec.Mul(overflowMask, TACExpr.Vec.Add(x.asSym(), k.asSym()))
        }

        /*
         * Matches the patterns identified in the toplevel documentation.
         *
         * @param numIterations the number of iterations of the loop summarized by [summary], if known
         * @param fixupExitVar if not null, then we are summarizing a loop of the form:
         *        while (fixupExitVar != 0) { ... }
         *
         * @return (effects, preconditions, identities) where:
         *         effects associates a subset of the modified
         *         variables with their update schemes;
         *
         *         each (x, k) in preconditions is
         *         the constraint (x % k == 0) that must hold for the effects to be valid
         *
         *         each x in identities is a variables whose value is unmodified
         */
        private fun iterationEffects(
            numIterations: BigInteger?,
            fixupExitVar: TACSymbol.Var?,
            summary: LoopIterationSummary,
            blaster: IBlaster,
        ): Triple<Map<TACSymbol.Var, LoopEffect>, Set<Pair<TACSymbol.Var, BigInteger>>, Set<TACSymbol.Var>> {

            val effects = mutableMapOf<TACSymbol.Var, LoopEffect>()
            val preconditions = mutableSetOf<Pair<TACSymbol.Var, BigInteger>>()
            val identity = mutableSetOf<TACSymbol.Var>()

            if (numIterations == BigInteger.ZERO) {
                return Triple(effects, preconditions, identity)
            }

            fun match(e1: TACExpr, e2: TACExpr) =
                LogicalEquivalence.equiv(listOf(), e1, e2, blaster)

            for ((x, eff) in summary.iterationEffect) {
                val init = initialValue(x, summary)?.second
                val consts = eff.subs.toConstSet()
                val y = TACExprFreeVarsCollector.getFreeVars(eff).singleOrNull { it != x }
                val yInit = y?.let { initialValue(it, summary) }

                if (eff is TACExpr.Sym && eff.s == x) {
                    identity += x
                    continue
                }

                // We need to guess some consts for the LogicalEquivalence check, so we just iterate over the ones
                // that appear in [eff]
                // if we find a match then we're done and we break.
                for (k in consts) {
                    if (k.value == BigInteger.ZERO) {
                        continue
                    }

                    // x = x + k when we don't know how many times the loop will execute is not very
                    // useful information downstream, so don't bother
                    if (init != null && numIterations != null && match(eff, TACExpr.Vec.Add(x.asSym(), k.asSym()))) {
                        // Constant effect: x += k
                        effects[x] = ConstantEffect(
                            init,
                            k.value
                        )
                        break
                    } else if (k.value <= 32.toBigInteger() &&
                        y != null &&
                        y in summary.iterationEffect &&
                        match(eff, packedSlotUpdate(x, y, k)) &&
                        match(summary.iterationEffect[y]!!, byteOffsetUpdate(y, k))
                    ) {
                        // x is a storage pointer to packed values (storageptr below)
                        // y is the byte offset within the slot x points to (byteOffset below):

                        /*
                         * while (memptr < K) {
                         *   vm = M[memptr]
                         *   vs = S[storageptr]
                         *   // bit ops nonsense to set the proper byte
                         *   S[storageptr] = vs'
                         *   memptr += 32
                         *   nextByteOffset = byteOffset + B
                         *   slotOverflow = (nextByteOffset + B-1)/32
                         *   storageptr = storageptr + slotOverflow
                         *   byteOffset = (1 - slotOverflow)*nextByteOffset
                         * }
                         */

                        val startByte = (yInit?.second as? TACSymbol.Const)?.value
                        // If we know how many iterations we're running, we can be precise.
                        if (init != null && startByte != null && startByte.mod(k.value) == BigInteger.ZERO && startByte < 32.toBigInteger() && numIterations != null) {
                            // Every time our [y], which is a byte pointer "overflows" we bump the storage slot.
                            // We're starting at the "startByte/k.value" iteration (possible that some unrolling happened),
                            // which is still storage slot 0
                            //
                            // See smt "proof" at bottom of this file [PROOF]
                            val numPacked = 32.toBigInteger() / k.value
                            effects[x] = SummarizedEffect(
                                init,
                                IntValue(BigInteger.ZERO, (numIterations - BigInteger.ONE) / numPacked),
                                IntValue.Constant(numIterations / numPacked),
                            )
                            // We reset the byte pointer every numPacked iterations:
                            effects[y] = BytePtrUpdate(startByte.asTACSymbol(), k.value)
                        } else if (y == fixupExitVar) {
                            // Same as the above loop except the condition is now (y != 0)
                            // so, we'll exit once we overflow the current slot
                            identity += x
                            // Only valid if we start with y % k.value == 0 && y < 32
                            preconditions += setOf(y to k.value)
                        }

                        break
                    }
                }
            }
            return Triple(effects, preconditions, identity)
        }

        /**
         * Try to summarize [l]
         */
        fun loopAnnotation(
            l: Loop,
            loopSummary: LoopSummarization,
            blaster: IBlaster
        ): Parallel<StorageCopyLoopSummary?> = Scheduler.compute {
            val candidate = storageToMemoryLoopCandidate(graph, l) ?: return@compute null
            val summary = loopSummary.summarizeLoop(l) ?: return@compute null
            val cond = graph.elab(l.head).commands.last().maybeNarrow<TACCmd.Simple.JumpiCmd>() ?: return@compute null

            val numIterations = numberOfIterations(cond.wrapped, summary, SmtExpIntBlaster())
            if (numIterations != null) {
                val (effects, preconditions, identity) = iterationEffects(numIterations, null, summary, blaster)
                StorageCopyLoopSummary(
                    numIterations = numIterations,
                    storageCmds = candidate.storageCmd,
                    modifiedVars = summary.iterationEffect.keys - identity,
                    effects = effects,
                    summarizedBlocks = l.body,
                    originalBlockStart = l.head,
                    skipTarget = candidate.succ,
                    loopInfeasible = null,
                    preconditions = preconditions
                )
            } else if (!summary.isDoLoop) {
                // This could be a packed fixup loop, whose bounds aren't apparent.
                val condVar = cond.cmd.cond as? TACSymbol.Var ?: return@compute null
                val byteOffsetVar =
                    fixupLoopPattern.query(condVar, cond.wrapped).toNullableResult() ?: return@compute null
                if (cond.cmd.dst != candidate.succ) {
                    return@compute null
                }
                val (effects, preconditions, identity) = iterationEffects(null, byteOffsetVar, summary, blaster)
                StorageCopyLoopSummary(
                    numIterations = null,
                    storageCmds = candidate.storageCmd,
                    modifiedVars = summary.iterationEffect.keys - identity,
                    effects = effects,
                    summarizedBlocks = l.body,
                    originalBlockStart = l.head,
                    skipTarget = candidate.succ,
                    loopInfeasible = TACExpr.BinRel.Eq(byteOffsetVar.asSym(), BigInteger.ZERO.asTACSymbol().asSym()),
                    preconditions = preconditions,
                )
            } else {
                null
            }
        }
    }

    fun annotateLoops(g: CoreTACProgram): CoreTACProgram {
        val graph = g.analysisCache.graph
        val patching = g.toPatchingProgram()
        val loops = getNaturalLoops(graph)
        return ParallelPool.allocInScope(2000, { timeout -> Z3BlasterPool(z3TimeoutMs = timeout) }) { blaster ->
            val worker = Worker(graph)
            val loopSummary = LoopSummarization(
                blaster = blaster,
                g = graph,
                loops = loops
            )

            val annotatedLoops = ParallelPool.runInherit(
                loops.forkEvery { loop ->
                    worker.loopAnnotation(loop, loopSummary, blaster).bind { complete(loop `to?` it) }
                }.pcompute().map { it.filterNotNull() }, ParallelPool.SpawnPolicy.GLOBAL
            )

            val processed = mutableSetOf<Loop>()
            for ((l, summary) in annotatedLoops) {
                processed.add(l)
                val summaryBlock = patching.addBlock(
                    l.head, listOf(
                        TACCmd.Simple.SummaryCmd(summary, MetaMap())
                    )
                )
                for (pred in graph.pred(l.head)) {
                    if (pred in l.body) {
                        continue
                    }

                    val lastCmd = graph.elab(pred).commands.last()
                    if (lastCmd.cmd is TACCmd.Simple.JumpCmd) {
                        patching.replaceCommand(
                            lastCmd.ptr, listOf(
                                lastCmd.cmd.withDst(summaryBlock)
                            )
                        )
                    } else {
                        patching.replaceCommand(lastCmd.ptr, listOf(lastCmd.cmd), treapSetOf(summaryBlock))
                    }
                }
            }
            patching.toCode(g)
        }

    }
}
/**
 * [PROOF]
 * Proof byte pointer (initially R81, updated version is R77) is always in [0, 31] if it starts in that range:
 * (declare-const R72 (_ BitVec 256))
 * (declare-const R73 (_ BitVec 256))
 * (declare-const R74 (_ BitVec 256))
 * (declare-const R76 (_ BitVec 256))
 * (declare-const R77 (_ BitVec 256))
 * (declare-const R81 (_ BitVec 256))
 *
 * (assert
 *  (= R72 (bvadd (_ bv1 256) R81)))
 * (assert
 *  (= R73 (bvadd (_ bv0 256) R72)))
 * (assert
 *  (= R74 (bvashr R73 (_ bv5 256))))
 * (assert
 *  (= R76 (bvsub (_ bv1 256) R74)))
 * (assert
 *  (= R77 (bvmul R76 R72)))
 *
 * (assert (bvult R81 (_ bv32 256)))
 * (assert (not (bvult R77 (_ bv32 256))))
 *
 * (check-sat)
 */
