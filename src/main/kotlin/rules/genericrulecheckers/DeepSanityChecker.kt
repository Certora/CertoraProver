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

package rules.genericrulecheckers

import allocator.SuppressRemapWarning
import analysis.*
import config.Config
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import rules.genericrulecheckers.msgvalueinloop.ErrorCmdGenerator
import scene.IScene
import scene.ITACMethod
import utils.Range
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.DeepSanityGenerator
import tac.MetaMap
import tac.NBId
import utils.mapToSet
import vc.data.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.GENERIC_RULE)

/**
 * A checker for the deepSanity rule.
 * It will pick select number of "good reachability points",
 * where reachability points are points we force a cex to go through.
 * The checker will instrument a TAC program that ends with asserts that the reachability points
 * have been reached, each one in isolation.
 * Note that the deepSanity rule will fail if multi_assert_check is disabled.
 * The reachability asserts will also run in isolation from one another, *unlike* the usual behavior of multi-assert.
 */
object DeepSanityChecker : InstrumentingBuiltinRuleChecker<DeepSanityGenerator>() {

    // will be at least +1 more because of the root check
    private val maxNumberOfReachabilityChecksToInstrumentBasedOnDomination =
        Config.MaxNumberOfReachChecksBasedOnDomination.get()

    // general utility methods
    private fun getSingleParent(block: NBId, graph: TACCommandGraph) =
        graph.pred(block).singleOrNull()

    private fun getSingleParentLastCmd(block: NBId, graph: TACCommandGraph) =
        getSingleParent(
            block,
            graph
        )?.let { parent ->
            graph.elab(parent).commands.lastOrNull()?.let { lcmd ->
                Worker.ParentAndPrecedingCommand(
                    parent,
                    lcmd
                )
            }
        }

    // worker class
    private class Worker(
        private val prog: CoreTACProgram,
        private val range: Range
    ) {
        data class ParentAndPrecedingCommand(val parent: NBId, val lastCmd: LTACCmd)

        /**
         * [ShouldBeReachablePoint] classes give information on the point we try to reach.
         */
        sealed class ShouldBeReachablePoint {
            // The pointer we are trying to reach
            abstract val ptr: CmdPointer

            // The containing graph
            abstract val graph: TACCommandGraph

            // A string for the assert message, this is UI facing!
            abstract fun computeAssertMsgCore(): String
            fun computeAssertMsg(exitPoint: CmdPointer): String = "${computeAssertMsgCore()}, uid ${ptr.block}-${ptr.pos}-${exitPoint.block}-${exitPoint.pos}"

            /**
             * The root of the graph.
             */
            data class Root(
                override val ptr: CmdPointer,
                override val graph: TACCommandGraph
            ) : ShouldBeReachablePoint() {
                override fun computeAssertMsgCore(): String = "root of method"
            }

            /**
             * A branching destination. As we may try to reach both branches of a particular [parent],
             * We make sure to include the path condition [pathCond] to disambiguate the assert message.
             * Ideally, this would be not low-level TAC info.
             * Otherwise, the sanity subrule could fail due to being mapped to the same ruleDeclarationId.
             * We provide the [dominatingSize] for perspective.
             */
            data class Branching(
                override val ptr: CmdPointer,
                override val graph: TACCommandGraph,
                val parent: NBId,
                val pathCond: PathCondition,
                val dominatingSize: Int
            ) : ShouldBeReachablePoint() {
                override fun computeAssertMsgCore(): String =
                    getSingleParentLastCmd(ptr.block, graph)
                        ?.lastCmd?.cmd?.sourceRange()
                        ?.let { range ->
                        "${range}, path condition $pathCond, dominating $dominatingSize low-level blocks"
                    } ?: "low-level block ${ptr.block}"
            }

            /**
             * An external call, with an associated [callSummary].
             */
            @SuppressRemapWarning
            data class ExternalCall(
                override val ptr: CmdPointer,
                override val graph: TACCommandGraph,
                val callSummary: CallSummary
            ) : ShouldBeReachablePoint() {
                override fun computeAssertMsgCore(): String =
                    "extcall ${callSummary.callType.name.lowercase()} in ${
                        graph.elab(ptr).cmd.sourceRange() ?: "unknown location"
                    }"
            }
        }

        private fun isRevertingBlock(b: NBId, graph: TACCommandGraph) =
            RevertBlockAnalysis.mustReachRevert(b, graph) || b in graph.cache.revertBlocks

        /**
         * Finds the top dominating nodes to be reached.
         */
        private fun findDominatingNodes(prog: CoreTACProgram): List<ShouldBeReachablePoint> {
            val graph = prog.analysisCache.graph
            val dominators = prog.analysisCache.domination
            val topDominatingBlocksWithParents = graph.blocks.sortedByDescending { block ->
                block.id.let { nbid ->
                    dominators.dominatedOf(nbid).size
                }
            }.mapNotNull { block ->
                // we want those that:
                // (1) have a single parent
                // (2) that parent ends with a conditional jump,
                // (3) it's not a reverting path
                // (4) the sibling is not reverting either
                getSingleParentLastCmd(block.id, graph)?.let { (parent, lastCmd) -> // 1 is checked
                    (block to parent).takeIf {
                        (lastCmd.cmd is TACCmd.Simple.JumpiCmd // 2 is checked
                            && !isRevertingBlock(block.id, graph)) && // 3 is checked
                            // 4 is checked
                            (graph.siblingOf(block.id, parent)?.let { sibling -> !isRevertingBlock(sibling, graph) }
                                ?: throw IllegalStateException(
                                    "${block.id} must have a single sibling with parent $parent but found children to be ${
                                        graph.succ(
                                            parent
                                        )
                                    }"
                                ))
                    }
                }
            }

            return topDominatingBlocksWithParents
                .take(
                    Math.min(
                        maxNumberOfReachabilityChecksToInstrumentBasedOnDomination,
                        topDominatingBlocksWithParents.size
                    )
                )
                .map { (b, parent) ->
                    ShouldBeReachablePoint.Branching(
                        CmdPointer(b.id, 0),
                        graph,
                        parent,
                        graph.pathConditionsOf(parent)[b.id]
                            ?: throw IllegalStateException("Parent $parent should have path condition for ${b.id}"),
                        dominators.dominatedOf(b.id).size
                    )
                }
        }

        /**
         * Finding external nodes to be reached.
         */
        private fun findExternalCallNodes(prog: CoreTACProgram): List<ShouldBeReachablePoint> {
            val graph = prog.analysisCache.graph
            return graph.commands.mapNotNull { lcmd ->
                lcmd.maybeNarrow<TACCmd.Simple.SummaryCmd>()
                    ?.cmd?.summ
                    ?.takeIf { it is CallSummary }
                    ?.let { summ ->
                        ShouldBeReachablePoint.ExternalCall(lcmd.ptr, graph, summ as CallSummary)
                    }
            }.toList()
        }

        private fun findNodesToReach(prog: CoreTACProgram): List<ShouldBeReachablePoint> {
            return (findDominatingNodes(prog) + findExternalCallNodes(prog)).also { nodesList ->
                check(nodesList.mapToSet { it.ptr }.size == nodesList.size) {
                    "The collection of reachability check nodes contains duplicates (grouping by cmdPtr): $nodesList"
                }
                logger.info { "For ${prog.name}, generating asserts for ${nodesList}"}
            }
        }

        private fun instrumentSanityCheck(
            prog: CoreTACProgram,
            nodesToReach: List<ShouldBeReachablePoint>,
            range: Range
        ): SimplePatchingProgram {
            val patching = prog.toPatchingProgram()
            fun createReachIndicatorVar(ptr: CmdPointer) =
                ErrorCmdGenerator.createErrorVar("reachIndicator${ptr.block}_${ptr.pos}")
                    .also { patching.addVarDecl(it) }

            val graph = prog.analysisCache.graph
            val root = graph.roots.singleOrNull()?.let { ShouldBeReachablePoint.Root(it.ptr, graph) }
                ?: throw IllegalStateException("Multiple roots in ${prog.name}")

            // reachability indicator variables
            val reachabilityIndicatorVars = nodesToReach
                .associate {
                    it to createReachIndicatorVar(it.ptr)
                }
            // root is added separately, because it will be true always, and at the root we also init to false the rest
            // of the indicator variables for the other nodes
            val rootReachIndicator = createReachIndicatorVar(root.ptr)

            // init reachability indicators
            reachabilityIndicatorVars.mapTo(mutableListOf()) { (_, reachIndicatorVar) ->
                TACCmd.Simple.AssigningCmd.AssignExpCmd(reachIndicatorVar, TACSymbol.False)
            }.let { mutList ->
                mutList.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(rootReachIndicator, TACSymbol.True))
                patching.insertAfter(root.ptr, mutList)
            }

            // assert reachability, including root
            val allPlusRoot = reachabilityIndicatorVars.plus(root to rootReachIndicator)
            val varsForAssert = allPlusRoot.map { (b, reachIndicatorVar) ->
                val reachCheck = ErrorCmdGenerator.createErrorVar("${reachIndicatorVar.namePrefix}Impl")
                    .also { patching.addVarDecl(it) }
                b to (reachIndicatorVar to reachCheck)
            }

            graph.sinks.filter { !isRevertingBlock(it.ptr.block, graph) }.forEach { sink ->
                logger.debug { "Adding asserts in sink ${sink.ptr}" }
                logger.debug { "Variables to assert on: $varsForAssert" }
                patching.addBefore(
                    sink.ptr,
                    varsForAssert.flatMap { (b, vars) ->
                        val (reachIndicatorVar, reachCheck) = vars
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                reachCheck,
                                // reachIndicator => false === !reachIndicator || false === !reachIndicator
                                TACExpr.UnaryExp.LNot(reachIndicatorVar.asSym())
                            ),
                            TACCmd.Simple.AssertCmd(
                                reachCheck,
                                "Reachability check in ${
                                    b.computeAssertMsg(sink.ptr)
                                }",
                                meta = MetaMap(TACMeta.CVL_RANGE to range)
                                    .plus(TACMeta.CVL_USER_DEFINED_ASSERT)
                                    .plus(TACMeta.ISOLATED_ASSERT) // to ensure other asserts are nop'd
                            ).also { assertCmd ->
                                logger.debug { "Added assert $assertCmd at ${sink.ptr}" }
                            }
                        )
                    }
                )
            }

            // set in right places
            reachabilityIndicatorVars.forEachEntry { (node, reachIndicatorVar) ->
                patching.addBefore(
                    node.ptr,
                    listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(reachIndicatorVar, TACSymbol.True))
                )
            }

            return patching
        }

        fun doWork(): SimplePatchingProgram {
            val nodesToReach = findNodesToReach(prog)
            return instrumentSanityCheck(prog, nodesToReach, range)
        }
    }

    override fun instrumentingTransform(
        scene: IScene,
        currentContractId: BigInteger,
        range: Range,
        m: ITACMethod
    ): CoreTACProgram {
        val prog = m.code as CoreTACProgram
        val newProg = Worker(prog, range).doWork()
        m.updateCode(newProg)
        return m.code as CoreTACProgram
    }

    override val eId: BuiltInRuleId
        get() = BuiltInRuleId.deepSanity


}
