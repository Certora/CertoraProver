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

package verifier.equivalence.summarization

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.PathCondition
import com.certora.collect.*
import datastructures.LinkedArrayHashMap
import datastructures.stdcollections.*
import tac.BlockIdentifier
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*

/**
 * A simpler version of the full [CanonicalTACProgram] and that infrastructure. It fundamentally
 * uses the same idea of the code rewriter. However, instead of attempting to canonicalize meta/summaries and so on,
 * we simply strip *all* of them out. In other words, this canonicalizes of the computational fragment
 * of a program.
 *
 * Additionally, this class is intended to canonicalize subgraphs *not* entire core tac programs (although the resulting
 * canonical program *can* be promoted to a full CTP, see below).
 *
 * The actual canonicalization algorithm is quite simple. Beginning from start point, the original program
 * [prog] is traversed in some canonical order (currently BFS-ish). All variables and block IDs encountered along this
 * walk are assigned some canonical representation using counters. Commands may be excluded via the [include] predicate;
 * if it returns false the command is skipped for canonicalization.
 *
 * As mentioned above, all meta is stripped from all variables and commands. Further, blocks are automatically collapsed:
 * if a block B has a single successor B', then the bodies of B and B' are merged together under the canonical ID
 * assigned to B.
 *
 *
 * A note on summaries:
 * If the canonicalization process encounters a [ConditionalBlockSummary],
 * it will automatically skip to the [ConditionalBlockSummary.originalBlockStart], ignoring whatever 'summary' information
 * is contained within the summary object.
 */
class SimpleCanonicalization private constructor(
    val variableCanonicalizer: VariableCanonicalizer?,
    val prog: CoreTACProgram,
    val end: (LTACCmd) -> Boolean,
    val include: (LTACCmd) -> Boolean,
    /**
     * Allows specifying that multiple variables should share a canonicalized name.
     * This is primarily used to unify the output variables for internal functions
     * (which is currently this class' only user).
     *
     * In particular, the return variables at different exit sites will have distinct names. By forcing those
     * variables to have the same canonical name (that is, return variable 0 always has name V0, return variable
     * 1 always has name V1, etc.) we can ensure that internal functions have identical output behavior
     */
    forceVariableEquiv: List<Collection<TACSymbol.Var>>
) {
    fun interface VariableCanonicalizer {
        fun canonicalize(v: TACSymbol.Var, namePrefix: String, fresh: () -> Int): TACSymbol.Var?
    }

    companion object {
        /**
         * Canonicalize a subgraph of [prog] beginning at [start] running until a
         * command for which [end] returns true. NB that the command for which [end] returns true is NOT
         * included in the canonicalized program.
         *
         * [start] is included in the canonicalized program depending on whether [excludeStart] is true; if it is, then
         * the canonical program begins with the unique successor of [start].
         *
         * [forceVariableEquiv] has the behavior described in the constructor; all variables in the collections
         * in the list are assigned the same variable. An exception will be thrown if a variable appears in multiple groups
         * within [forceVariableEquiv].
         *
         * [include] is a predicate which indicates whether a command should be included in the canonicalized program.
         */
        fun canonicalize(
            prog: CoreTACProgram,
            start: CmdPointer,
            excludeStart: Boolean,
            forceVariableEquiv: List<Collection<TACSymbol.Var>> = listOf(),
            variableCanonicalizer: VariableCanonicalizer? = null,
            include: (LTACCmd) -> Boolean,
            end: (LTACCmd) -> Boolean
        ): CanonicalProgram {
            return SimpleCanonicalization(variableCanonicalizer, prog, end, include, forceVariableEquiv).canonicalizeSubgraph(start, excludeStart)
        }
    }

    private var blockCounter = 0

    /**
     * Maps an [NBId] to its canonical representation.
     */
    private val canonBlocks = mutableMapOf<NBId, NBId>()

    private fun NBId.forwardMap() = canonBlocks.getOrPut(this) {
        val id = blockCounter++
        id.toBlock()
    }

    var varCounter = 0

    /**
     * Maps a [TACSymbol.Var] to its canonical representation.
     */
    val canonVars = mutableMapOf<String, TACSymbol.Var>()

    init {
        for(eq in forceVariableEquiv) {
            val reprId = varCounter++
            check(eq.allSame { it.tag }) {
                "Different tags in equivalence group"
            }
            for(mem in eq) {
                require(mem.namePrefix !in canonVars) {
                    "Multiple canonicalizations provided for $mem"
                }
                canonVars[mem.namePrefix] = reprId.toVar(mem.tag)
            }
        }
    }

    val blockGraph = mutableMapOf<NBId, Set<NBId>>()
    val code = mutableMapOf<NBId, List<TACCmd.Simple>>()

    /**
     * Simple mapper which canonicalizes [NBId] and [TACSymbol.Var] within a command
     */
    private val canonicalizer = object : DefaultTACCmdMapper() {
        override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
            check(t.callIndex == NBId.ROOT_CALL_ID)
            return canonVars.getOrPut(t.namePrefix) {
                val canonOverride = variableCanonicalizer?.canonicalize(t, t.namePrefix) {
                    varCounter++
                }
                if(canonOverride != null) {
                    return@getOrPut canonOverride
                }
                val freshId = varCounter++
                freshId.toVar(t.tag)
            }.also { cVar ->
                check(cVar.tag == t.tag) {
                    "Tag of variable got switched during canonicalization $t -> $cVar"
                }
            }
        }

        override fun mapJumpICmd(dst: NBId, cond: TACSymbol, elseDst: NBId, metaMap: MetaMap): TACCmd.Simple {
            return TACCmd.Simple.JumpiCmd(
                cond = mapSymbol(cond),
                dst = dst.forwardMap(),
                elseDst = elseDst.forwardMap(),
                meta = MetaMap()
            )
        }

        /**
         * This implementation is included for completeness, but it is expected that users will always strip out
         * [vc.data.TACCmd.Simple.JumpdestCmd] via the [include] predicate.
         */
        override fun mapJumpDstCmd(t: TACCmd.Simple.JumpdestCmd): TACCmd.Simple {
            return TACCmd.Simple.JumpdestCmd(
                startPC = t.startPC.forwardMap()
            )
        }

        override fun mapJumpCmd(t: TACCmd.Simple.JumpCmd): TACCmd.Simple {
            return TACCmd.Simple.JumpCmd(
                dst = t.dst.forwardMap()
            )
        }

        /**
         * This is called for each meta on commands passed to [DefaultTACCmdMapper.map] as
         * well as variables that appear within, including within [TACExpr]. Thus, this
         * has the effect of stripping all meta from the program.
         */
        override fun mapMeta(t: MetaMap): MetaMap {
            return MetaMap()
        }
    }

    class CanonicalProgram(
        val code: CoreTACProgram,
        val variableMapping: (TACSymbol.Var) -> TACSymbol.Var?
    ) {
        infix fun equivTo(other: CanonicalProgram) : Boolean {
            return code.blockgraph == other.code.blockgraph && code.code == other.code.code
        }
    }

    private fun Int.toBlock() = BlockIdentifier(
        origStartPc = this,
        calleeIdx = 0,
        freshCopy = 0,
        topOfStackValue = 0,
        stkTop = if(this == 0) { 0 } else { 1 },
        decompCopy = 0
    )

    private fun Int.toVar(t: Tag) = TACSymbol.Var("V$this", tag = t)

    /**
     * The actual start of the canonicalization routine. [start] and [excludeStart] have
     * the same interpretation as [Companion.canonicalize].
     */
    private fun canonicalizeSubgraph(
        start: CmdPointer,
        excludeStart: Boolean,
    ) : CanonicalProgram {
        val worklist = ArrayDeque<Pair<CmdPointer, Boolean>>()
        worklist.add(
            start to excludeStart
        )
        worklist.consume {(itStart, itExcludeStart) ->
            /**
             * if we've already canonicalized this block, skip
             */
            if(itStart.block.forwardMap() in code) {
                return@consume
            }
            val lastCommand = translateBlock(itStart, itExcludeStart) ?: return@consume
            val nxtPc = graph.pathConditionsOf(lastCommand)
            if(nxtPc.isEmpty()) {
                return@consume
            }
            if(nxtPc.size == 1) {
                val nxt = nxtPc.findEntry { _, pc ->
                    pc is PathCondition.TRUE
                }!!.first
                worklist.add(nxt to false)
                blockGraph[itStart.block.forwardMap()] = setOf(nxt.block.forwardMap())
                return@consume
            }
            val trueTarget = nxtPc.findEntry { _, pc ->
                pc is PathCondition.NonZero
            }!!.first
            val falseTarget = nxtPc.findEntry { _, pc ->
                pc is PathCondition.EqZero
            }!!.first
            worklist.add(trueTarget to false)
            worklist.add(falseTarget to false)
            blockGraph[itStart.block.forwardMap()] = setOf(
                trueTarget.block.forwardMap(),
                falseTarget.block.forwardMap()
            )
        }
        for((b, _) in code) {
            if(b !in blockGraph) {
                blockGraph[b] = treapSetOf()
            }
        }
        val canonProgram = CoreTACProgram(blockgraph = blockGraph.mapValuesTo(LinkedArrayHashMap()) {
            it.value.toTreapSet()
        }, code = code, check = true, instrumentationTAC = InstrumentationTAC(UfAxioms.empty()), symbolTable = TACSymbolTable.withTags(canonVars.values.toSet()), procedures = setOf(), name = "canon")
        return CanonicalProgram(canonProgram, variableMapping = {
            canonVars[it.namePrefix]
        })
    }

    val graph get() = prog.analysisCache.graph

    /**
     * Actually performs the canonicalization of commands, starting from [start] (inclusive/excusive as determined by [excludeStart]).
     * The canonicalized commands are placed in [out], which is ultimately placed into [code] by the 2 argument version
     * of [translateBlock].
     *
     * If this function reaches the end of the block containing [start] without hitting an [end] command
     * AND this block has a single successor, the function will proceed to canonicalize this single successor block,
     * placing the results in [out]. In other words, the canonicalization process does the work
     * of [verifier.BlockMerger] during the canonicalization process.
     *
     * Thus, it is strongly recommended that users of this class exclude [vc.data.TACCmd.Simple.JumpCmd] and [vc.data.TACCmd.Simple.JumpdestCmd]
     * commands from the canonicalization.
     */
    private tailrec fun translateBlock(start: CmdPointer, excludeStart: Boolean, out: MutableList<TACCmd.Simple>) : CmdPointer? {
        for(lc in graph.iterateBlock(start, excludeStart = excludeStart)) {
            if(end(lc)) {
                return null
            } else if(!include(lc)) {
                continue
            }
            out.add(canonicalizer.map(lc.cmd))
        }
        val outPc = graph.pathConditionsOf(start.block)
        val succ = graph.succ(start.block).singleOrNull {
            outPc[it]?.let { pc ->
                pc == PathCondition.TRUE ||
                    (pc is PathCondition.Summary && pc.s.originalBlockStart == it)
            } == true && graph.pred(it) == setOf(start.block)
        } ?: return graph.elab(start.block).commands.last().ptr
        return translateBlock(CmdPointer(succ, 0), false, out)
    }

    /**
     * Canonicalizes a block beginning at [start]. The result of this canonicalization
     * is placed into [code] by this function; the block ID for this code is the
     * [forwardMap] of the block of [start].
     *
     * This function returns a non-null [CmdPointer] if a branch was encountered, the returned [CmdPointer]
     * is the location of the branch and should be handled by the worklist that calls this function.
     *
     * A null return value indicates that exploration stopped, i.e., we reached some command
     * for which [end] returned true along an unconditional path from [start].
     */
    private fun translateBlock(start: CmdPointer, excludeStart: Boolean) : CmdPointer? {
        val block = start.block.forwardMap()
        check(block !in code)
        val translatedCode = mutableListOf<TACCmd.Simple>()
        val ret = translateBlock(start, excludeStart, translatedCode)
        if(translatedCode.isEmpty()) {
            translatedCode.add(TACCmd.Simple.NopCmd)
        }
        code[block] = translatedCode
        return ret
    }
}
