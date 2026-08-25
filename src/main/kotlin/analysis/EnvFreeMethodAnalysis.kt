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

package analysis

import analysis.worklist.SimpleWorklist
import datastructures.stdcollections.*
import tac.NBId
import vc.data.*
import java.util.stream.Collectors

object EnvFreeMethodAnalysis {

    /**
     * Represents the choice to choose the if-then or else branch of a
     * JumpiCmd.
     */
    private enum class JumpBranch {
        THEN,
        ELSE
    }

    /**
     * Function to check that a [CoreTACProgram] definitely reverts after a check for msg.value > 0.
     * @param program the program object
     * @return true if the program definitely reverts after a check for msg.value > 0 that dominates the
     * program returns.
     */
    private fun methodRevertsWhenCallvalueGTZero(program: CoreTACProgram): Boolean {

        val commandGraph = program.analysisCache.graph

        // Find all the blocks with return commands for use in domination analysis.
        val returnBlocks = program.code.asSequence().fold(mutableSetOf<NBId>()) { set, block ->
            if (block.value.last() is TACCmd.Simple.ReturnCmd) {
                set.add(block.key)
            }
            set
        }

        // Run the domination analysis. We use the returnBlocks and dominationAnalysis
        // to determine if a block dominates all return cases.
        val dom = commandGraph.cache.domination

        /**
         * check if a given block dominates all the return blocks in a program.
         */
        fun blockDominatesAllReturns(block: NBId): Boolean = returnBlocks.all {
            dom.dominates(block, it)
        }

        /**
         * Determine if [id] is a unique dominance frontier in the set of [frontiers]
         * @param[id] the block id to consider for uniqueness
         * @param[frontiers] the set of frontiers where [id] must be unique
         *
         * Used as a helper function in blocksJoinAt
         */
        fun uniqueDominanceFrontier(id: NBId, frontiers: Set<NBId>): Boolean =
            frontiers.isNotEmpty() && frontiers.size == 1 && frontiers.contains(id)

        /**
         * get the set of block ids where blocks 'a' and 'b' join
         *
         * @return the set of blocks where the blocks join. If the blocks do not join
         * the function returns an empty set.
         */
        fun blocksJoinAt(a: NBId, b: NBId): Set<NBId> {
            val dominanceFrontiers = commandGraph.cache.domination.dominanceFrontiers

            val frontiersForA = dominanceFrontiers[a]
            val frontiersForB = dominanceFrontiers[b]

            if (frontiersForA != null && frontiersForB != null) {
                if (uniqueDominanceFrontier(b, frontiersForA)) {
                    return setOf(b)
                } else if (uniqueDominanceFrontier(a, frontiersForB)) {
                    return setOf(a)
                }
                return frontiersForA.filterTo(mutableSetOf()) {
                    frontiersForB.contains(it)
                }
            }
            return setOf()
        }

        // There are several ways that Solidity and/or Vyper code checks if callvalue != 0.  This pattern matches the
        // ones we know about, and returns the branch that should revert.  (Note that some of these are not emitted
        // directly by the compiler, but they look like this after prior TAC transforms have been applied.)
        val whichBranchShouldRevert = PatternDSL.build {
            val callvalue = !TACKeyword.CALLVALUE.toVar()

            (callvalue `==` 0()).commute.withAction { _, _ -> JumpBranch.ELSE } lor
            (callvalue gt 0()).withAction { _, _ -> JumpBranch.THEN } lor
            (0() lt callvalue).withAction { _, _ -> JumpBranch.THEN } lor
            ((callvalue or Var).commute).withAction { _, _ -> JumpBranch.THEN} lor
            ((callvalue or Var).commute gt 0()).withAction { _, _ -> JumpBranch.THEN}
        }.let {
            PatternMatcher.compilePattern(commandGraph, it)
        }

        /**
         * The SimpleWorkList provides a mechanism to iterate through instructions in the code and follow
         * instructions based on decisions about branches.  We pass in the set of root blocks and then
         * go through the instructions of the root block.  We check each instruction for a JumpiCmd. If the
         * command tests do not find a JumpiCmd, the successor to the root blocks get added to the set of
         * blocks to examine next for JumpiCmds.
         */
        val roots = commandGraph.rootBlocks.map { it.id }
        return SimpleWorklist.iterateWorklist(roots) { currentBlockID, nextSetOfIDs, exitF ->

            // Here, nextSetOfIDs is a mutable set to which we can add block IDs.  The added block
            // IDs constitute the next blocks to examine when we iterate again through the loop.

            // exitF is the exit continuation that we call to:
            // - set the worklist return value
            // - terminate the worklist early.

            // We need to maintain the property that the block and its successors _must_ revert when
            // msg.value > 0. If we work on a block that does not dominate the return values, then we
            // have reached a point where we can no longer guarantee the _must_ revert property.
            if (!blockDominatesAllReturns(currentBlockID)) {
                exitF(false)
                return@iterateWorklist
            }

            // graph.elab(blockID) gets the block for the ID.
            val block = commandGraph.elab(currentBlockID)

            // We need to look for JumpiCmd objects, those are always the last command in the block.
            val lcommand = block.commands[block.commands.lastIndex]
            val command = lcommand.cmd

            if (command is TACCmd.Simple.JumpiCmd) {
                val shouldRevert = (command.cond as? TACSymbol.Var)?.let { v ->
                    when (whichBranchShouldRevert.query(v, lcommand).toNullableResult()) {
                        JumpBranch.THEN -> command.dst
                        JumpBranch.ELSE -> command.elseDst
                        null -> null
                    }
                }
                if (shouldRevert != null) {
                    // Check that the branch which should revert, does revert
                    exitF(RevertBlockAnalysis.mustReachRevert(shouldRevert, commandGraph))
                    return@iterateWorklist
                } else {
                    // The jump condition isn't one of the ones we recognize; keep looking.

                    val thenBranchReverts = RevertBlockAnalysis.mustReachRevert(command.dst, commandGraph)
                    val elseBranchReverts = RevertBlockAnalysis.mustReachRevert(command.elseDst, commandGraph)

                    if (thenBranchReverts && elseBranchReverts) {
                        exitF(true)
                        return@iterateWorklist
                    } else if (!thenBranchReverts && elseBranchReverts) {
                        nextSetOfIDs.add(command.dst)
                    } else if (thenBranchReverts) {
                        nextSetOfIDs.add(command.elseDst)
                    } else {
                        // Neither branch reverts.

                        // find the set of block ids where the conditional jump targets join.
                        val joinIds = blocksJoinAt(command.dst, command.elseDst)
                        if (joinIds.isNotEmpty()) {
                            // The branches join so add those block ids to the work list
                            // for the next iteration.
                            nextSetOfIDs += joinIds
                        } else {
                            exitF(false)
                            return@iterateWorklist
                        }
                    }
                }
            } else if (command is TACCmd.Simple.RevertCmd) {
                // this block reverts, so not interesting (we could reach here in case the whole program is just a single, reverting, block).
                exitF(true)
                return@iterateWorklist
            } else {
                nextSetOfIDs += commandGraph.succ(currentBlockID)
            }
        } ?: false
    }

    /**
     * Function to check that a program does not access restricted symbols nor try to
     * write to restricted symbols
     * @param program the code to check
     * @return true if the function accesses or tries to write to some sub-set of symbols pertaining
     * to env.
     *
     * Current symbols/vars that cannot be read:
     * - tacBlockhash
     * - tacCaller
     * - tacCallvalue
     * - tacOrigin
     * - tacBasefee
     * - tacBlobhashes
     * - tacBlobbasefee
     * - tacCoinbase
     * - tacDifficulty
     * - tacGaslimit
     * - tacNumber
     * - tacSlotnum
     * - tacTimestamp
     *
     * Current symbols to which a program should not write:
     * - tacCallvalue
     *
     * This function replaces checks from RuleChecker.checkStaticEnvfreedom().
     */
    private fun methodDoesNotUseRestrictedEnvironmentSymbols(program: CoreTACProgram): Set<TACKeyword> {
        val accessForbiddenVariables = listOf(
            TACKeyword.BLOCKHASH,
            // msg
            TACKeyword.CALLER,
            // tx
            TACKeyword.BLOBHASHES,
            TACKeyword.ORIGIN,
            // block
            TACKeyword.BASEFEE,
            TACKeyword.BLOBBASEFEE,
            TACKeyword.COINBASE,
            TACKeyword.DIFFICULTY,
            TACKeyword.GASLIMIT,
            TACKeyword.NUMBER,
            TACKeyword.SLOTNUM,
            TACKeyword.TIMESTAMP,
        )
        check(!accessForbiddenVariables.any { keyword ->
            keyword.isName { it.contains(".") }
        }) { "accessForbiddenVariables list contains variables that contain a '.' character."}

        val writeForbiddenVariables = listOf(
            /* Only for CALLVALUE we allow reading the value, but still not writing.
               We expect all 'envfree' functions to revert immediately if being call with value != 0, so in fact they
               are reading the constant '0' rather than a parameter, writing would be something different.
             */
            TACKeyword.CALLVALUE
        )
        check(!writeForbiddenVariables.any { keyword ->
            keyword.isName { it.contains(".") }
        }) { "writeForbiddenVariables list contains variables that contain a '.' character."}

        return program.parallelLtacStream().map {
            if (it.cmd is TACCmd.Simple.AnnotationCmd
                || it.cmd is TACCmd.Simple.SummaryCmd
                || it.cmd.meta.containsKey(TACMeta.TRANSFERS_BALANCE)
            ) {
                return@map setOf() // annotation and summary commands are not part of the real source
                // so for example delegate calls that forward callvalue shouldn't be taken into account here
            }

            val lhs = it.cmd.getLhs()
            val lhsSymbols = lhs?.let { sym -> listOf(sym) } ?: listOf()
            val rhsSymbols = it.cmd.getRhs()

            fun symbolIsForbidden(symbol: TACSymbol, keywords: List<TACKeyword>): TACKeyword? {
                if (symbol !is TACSymbol.Var) {
                    return null
                }
                return keywords.find { keyword ->
                    symbol.meta[TACSymbol.Var.KEYWORD_ENTRY]?.name == keyword.getName()
                }
            }

            val doesAccessForbiddenVars = (lhsSymbols + rhsSymbols)
                .mapNotNull { symbol -> symbolIsForbidden(symbol, accessForbiddenVariables) }
            val doesWriteToForbiddenVars = lhsSymbols
                .mapNotNull { symbol -> symbolIsForbidden(symbol, writeForbiddenVariables) }
            doesAccessForbiddenVars + doesWriteToForbiddenVars
        }.collect(Collectors.toSet()).flatten().toSet()
    }

    /**
     * Function to return an EnvfreeInfo object which contains the information about whether
     * the program is envfree and if not, why the program is not envfree.
     * @param program the [CoreTACProgram] object containing the TAC code.
     * @return a [EnvfreeInfo] object containing two items; whether the method is envfree
     * and if not the reason that the method is not envfree.
     */
    fun analyzeMethodForEnvfree(program: CoreTACProgram): EnvfreeInfo {
        if (! methodRevertsWhenCallvalueGTZero(program)) {
            return EnvfreeInfo.Payable
        }

        val usedRestrictedKeywords = methodDoesNotUseRestrictedEnvironmentSymbols(program)
        if (usedRestrictedKeywords.isNotEmpty()) {
            return EnvfreeInfo.PropertyAccess(usedRestrictedKeywords)
        }

        return EnvfreeInfo.EnvFree
    }
}

/**
 * Encapsulate the following ideas:
 *
 * 1. [EnvFree] - the method is envfree.
 * 2. [Payable] - the method is payable and therefor not envfree.
 * 3. [PropertyAccess] - the method access some environment properties and is therefor not envfree
 */
sealed class EnvfreeInfo {
    data object EnvFree : EnvfreeInfo() {
        override val envfree : Boolean get() = true
    }

    data object Payable : EnvfreeInfo() {
        override val envfree: Boolean get() = false
    }

    data class PropertyAccess(val accessedProperties: Set<TACKeyword>) : EnvfreeInfo() {
        override val envfree: Boolean get() = false
    }

    abstract val envfree: Boolean
}
