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

import allocator.Allocator
import analysis.CmdPointer
import analysis.CommandWithRequiredDecls
import analysis.LTACCmd
import analysis.maybeNarrow
import kotlinx.coroutines.*
import log.*
import report.DummyLiveStatsReporter
import rules.CompiledRule
import scene.IScene
import solver.SolverResult
import spec.rules.EquivalenceRule
import tac.MetaKey
import tac.MetaMap
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.toCore
import verifier.TACVerifier
import verifier.Verifier
import verifier.equivalence.data.MethodMarker
import verifier.equivalence.data.CallableProgram
import verifier.equivalence.data.CallableProgram.Companion.map
import kotlin.jvm.optionals.getOrNull
import datastructures.stdcollections.*

private val logger = Logger(LoggerTypes.EQUIVALENCE)

/**
 * Matches exit locations in two programs, that is, if control-flow reaches one exit site in program A, under the
 * same inputs, what exit sites in program B could be reached? [ruleGeneration] is used to query "same inputs".
 */
class ExitMatcher<I> private constructor(private val scene: IScene, private val ruleGeneration: AbstractRuleGeneration<I>) {
    companion object {
        /**
         * Does the actual matching of sites in [progA] to exit sites in [progB]. Both must be loop free programs.
         * [ruleGeneration] is responsible for binding "equal inputs" for [progA] and [progB].
         *
         * A non-null return indicates that if `k` is mapped to `[e1, e2, ...]`, then when [progA] exits via `k`
         * [progB] exits via one of `e1`, `e2`, ... `en`
         */
        suspend fun <I> matchExits(
            scene: IScene,
            progA: CallableProgram<MethodMarker.METHODA, I>,
            progB: CallableProgram<MethodMarker.METHODB, I>,
            ruleGeneration: AbstractRuleGeneration<I>
        ): Map<CmdPointer, Collection<CmdPointer>>? {
            /**
             * We find the pairings per exit site in A.
             */
            val exitSiteA = progA.program.getEndingBlocks().map {
                progA.program.analysisCache.graph.elab(it).commands.last().ptr
            }
            val matcher = ExitMatcher(
                scene, ruleGeneration
            )

            /**
             * If any exit site search fails, kill off all the others
             */
            val job = Job()
            return try {
                withContext(job) {
                    exitSiteA.mapIndexed { index, siteA ->
                        async {
                            siteA to matcher.matchExitSites(
                                siteA, index, progA, progB
                            )
                        }
                    }.awaitAll().toMap()
                }
            } catch(t: CancellationException) {
                throw t
            } catch(t: Throwable) {
                logger.warn(t) {
                    "Failed extracting exit matching"
                }
                null
            }
        }

        private val ASSERTION_NUMBER = MetaKey<Int>("loop.exit.matching")
    }

    /**
     * For the exit site [siteA] in [progA] (given an arbitrary index [siteIndex]),
     * what are the exit sites in [progB] that correspond. If this query fails, this function throws,
     * the caller must catch.
     */
    private suspend fun matchExitSites(
        siteA: CmdPointer,
        siteIndex: Int,
        progA: CallableProgram<MethodMarker.METHODA, I>,
        progB: CallableProgram<MethodMarker.METHODB, I>,
    ) : Collection<CmdPointer> {
        logger.debug {
            "starting search for $siteA with index $siteIndex"
        }

        val graphB = progB.program.analysisCache.graph
        val bExits = progB.program.getEndingBlocks().map {
            graphB.elab(it).commands.last().ptr
        }
        logger.debug {
            "Exit site labeling for $siteA with $siteIndex is ${bExits.mapIndexed { ind, where ->
                "$where = $ind"
            }.joinToString(separator = ", ", prefix = "[", postfix = "]")}"
        }
        /**
         * Force control-flow to reach [siteA].
         */
        val progASelected = progA.map { c ->
            c.patchEachExit { where, p ->
                if(where.ptr == siteA) {
                    p.replaceCommand(where.ptr, listOf(TACCmd.Simple.NopCmd))
                } else {
                    p.replaceCommand(where.ptr, listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "not target")))
                }
            }
        }

        /**
         * Arbitrarily label the exit sites in B with [ASSERTION_NUMBER], and add an "assert false" at each such exit site.
         * If we get a SAT result from the solver, this tells us that it was possible for control flow to reach the `assert false`
         * site if [progA] exits via [siteA]. see [exitSearch].
         */
        var idCounter = 0
        val progBWithLabels = progB.map { c ->
            c.patchEachExit { where, patchingProgram ->
                val id = idCounter++
                patchingProgram.replaceCommand(where.ptr, listOf(
                    TACCmd.Simple.AssertCmd(
                        TACSymbol.False,
                        "exit site $id",
                        MetaMap(ASSERTION_NUMBER to id)
                    )
                ))
            }
        }
        /**
         * Start the search.
         */
        return exitSearch(
            siteIndex,
            progASelected,
            progBWithLabels,
            bExits,
            0 .. bExits.lastIndex
        )
    }

    /**
     * Dummy vc, assertions occur in the program.
     */
    private val vcProgram = CommandWithRequiredDecls(listOf(TACCmd.Simple.LabelCmd("and done"))).toCore("dummy vc", Allocator.getNBId())

    /**
     * [progASelected] is a version of the A program where control flow is forced to exit via a command pointer with
     * label [siteA]. [progB] has been instrumented to assert false at every exit site; each such exit site has a numeric
     * label, which is an index into [bExits].
     *
     * [range] is a range of indices in [bExits]. Our [exitSearch] proceeds as follows. We first preprocess [progB]
     * to exclude all exit sites not indicated by [range] (by replacing the "assert false" with an "assume false").
     * We then invoke the solver. If it returns unsat, that means none of the exit sites in denoted by [range]
     * are reachable when [progASelected] exits via [siteA]. In this case, we return an empty list.
     *
     * Otherwise, on sat the counter example is analyzed to find the exit site which was reachable. Let the id
     * of this exit site be `i`. We then partition [range] into two sub ranges which exclude `i`, and
     * then recursively invoke [exitSearch]. The result of these recursive invocations is the concatenated with
     * the singleton list containing the [CmdPointer] in [bExits] at index `i` and returned.
     *
     * On a timeout, we simply divide the range into two halves and try again on the two subproblems. If the [range] is
     * not further divisible, this throws, which should abort the entire exit matching process.
     *
     * When the entire tree of computation returns, we will have a complete collection of all exit sites in [progB]
     * reachable when [progASelected] exits via the select exit site.
     */
    private suspend fun exitSearch(
        siteA: Int,
        progASelected: CallableProgram<MethodMarker.METHODA, I>,
        progB: CallableProgram<MethodMarker.METHODB, I>,
        bExits: List<CmdPointer>,
        range: IntRange
    ) : Collection<CmdPointer> {
        if(range.isEmpty()) {
            return listOf()
        }
        /**
         * Replace exit site indicex *not* in [range] with an assume false.
         */
        val toInstrument = bExits.mapIndexedNotNull { ind, where ->
            if(ind !in range) {
                { p: SimplePatchingProgram ->
                    p.replaceCommand(where, listOf(TACCmd.Simple.AssumeCmd(TACSymbol.False, "ignored")))
                }
            } else {
                null
            }
        }
        /**
         * instrument
         */
        val selectedB = progB.map { wrapped ->
            wrapped.patching { p ->
                toInstrument.forEach { thunk ->
                    thunk(p)
                }
            }
        }

        /**
         * Again the [vcProgram] is just a dummy program, the actual interesting assertions have been
         * inlined into [progB].
         */
        val ruleName = "A${siteA}-B${range.joinToString("_")}"
        val vc = ruleGeneration.generateRule(
            progASelected,
            selectedB,
            vc = vcProgram,
            label = "exitMatch-$ruleName"
        )

        val subRule = EquivalenceRule.freshRule(ruleName)
        val res = TACVerifier.verify(
            scene.toIdentifiers(), tacObject = vc.code.copy(
                name = ruleName
            ), rule = subRule, liveStatsReporter = DummyLiveStatsReporter
        )
        val joinedResult = Verifier.JoinedResult(res)
        if(res.finalResult == SolverResult.UNSAT) {
            return listOf()
        } else if(res.finalResult == SolverResult.TIMEOUT) {
            if(range.singleOrNull() == null) {
                logger.debug {
                    "Timeout, splitting and retrying"
                }
                val halfSize = (range.last - range.first + 1) / 2
                val firstRange = range.first..< range.first + halfSize
                val secondRange = range.first + halfSize .. range.last
                return coroutineScope {
                    val job1 = async {
                        exitSearch(siteA, progASelected, progB, bExits, firstRange)
                    }
                    val job2 = async {
                        exitSearch(siteA, progASelected, progB, bExits, secondRange)
                    }
                    job1.await() + job2.await()
                }
            }
        } else if(res.finalResult != SolverResult.SAT) {
            throw IllegalStateException("Failed to match exits for $siteA -> ${range.map { bExits[it] }}")
        }
        val failure = joinedResult as Verifier.JoinedResult.Failure
        val failedProg = CompiledRule.addAssertIDMetaToAsserts(
            failure.simpleSimpleSSATAC,
            subRule
        )

        /**
         * Find the assertion that failed.
         */
        val failedAssertId = failedProg.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()
        }.filter { lcAssert ->
            lcAssert.ptr.block in res.examplesInfo.head.model.reachableNBIds
        }.filter { lcAssert ->
            res.examplesInfo.head.model.valueAsBoolean(lcAssert.cmd.o).leftOrNull() == false
        }.findFirst().getOrNull()?.cmd?.meta?.get(ASSERTION_NUMBER) ?: throw IllegalStateException("Failed VC but didn't find labelled failing assert")

        /**
         * Find the index of the matching cmd pointer. This indirection is required because
         * [ASSERTION_NUMBER] isn't necessarily the index into [bExits] (maybe it should be?)
         */
        val failedAssert = selectedB.program.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssertCmd>()
        }.filter {
            it.cmd.meta[ASSERTION_NUMBER] == failedAssertId
        }.map {
            it.ptr
        }.findFirst().getOrNull() ?: throw IllegalStateException("Couldn't find cmd pointer of assert with id $failedAssertId")
        val assertInd = bExits.indexOf(failedAssert)
        if(assertInd < 0) {
            throw IllegalStateException("$failedAssert was not in list")
        }
        logger.debug {
            "Found match for $siteA at $failedAssert"
        }
        val subQuery1 = range.first until assertInd
        val subQuery2 = assertInd + 1 .. range.last
        return coroutineScope {
            val subJobA = async {
                exitSearch(
                    siteA, progASelected, progB, bExits, subQuery1
                )
            }
            val subJobB = async {
                exitSearch(siteA, progASelected, progB, bExits, subQuery2)
            }
            listOf(failedAssert) + subJobA.await() + subJobB.await()
        }
    }

    private fun CoreTACProgram.patchEachExit(f: (LTACCmd, SimplePatchingProgram) -> Unit): CoreTACProgram {
        val thunks = this.getEndingBlocks().map {
            val lst = analysisCache.graph.elab(it).commands.last();
            { p: SimplePatchingProgram ->
                f(lst, p)
            }
        }
        return this.patching { p ->
            for(t in thunks) {
                t(p)
            }
        }
    }
}
