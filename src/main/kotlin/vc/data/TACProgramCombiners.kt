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

package vc.data

import analysis.CommandWithRequiredDecls
import analysis.TACExprWithRequiredCmdsAndDecls
import analysis.merge
import com.certora.collect.*
import config.ReportTypes
import datastructures.LinkedArrayHashMap
import datastructures.stdcollections.*
import log.*
import spec.CVLCompiler
import spec.RETURN_VALUE
import tac.DumpTime
import tac.NBId
import tac.StartBlock
import utils.*

/**
 * Contains functionality to combine and create TACPrograms, sequentially, parallel, and from single commands.
 */

private val logger = Logger(LoggerTypes.SPEC)

/**
 * @return all leaves that are part of normal control flow (return annotations indicate breaking from control
 *          flow to return to the return site of a [CVLFunction] call.
 *
 * @see [RETURN_VALUE]
 *
 * not equivalent to [TACProgram.getEndingBlocks] because of the handling of returns
 */
private fun getLeaves(c : TACProgram<*>) : Set<NBId> {
    return c.blockgraph.filter { (node, children) ->
        children.isEmpty() &&
                c.code[node]?.none { cmd ->
                    cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.k == RETURN_VALUE
                }?: true // no code, I guess it's still a leaf
    }.keys
}

object TACProgramCombiners {
    fun mergeCodes(first: CoreTACProgram, second: CoreTACProgram) : CoreTACProgram {
        if (first.isEmptyCode()) {
            return second
        }
        if (second.isEmptyCode()) {
            return first
        }

        val rootId = second.rootBlock.id
        return first.copy(
            code = first.code + second.code,
            blockgraph = LinkedArrayHashMap<NBId, TreapSet<NBId>>().also {
                it.putAll(first.blockgraph)
                it.putAll(second.blockgraph)
                for(end in first.getEndingBlocks()) {
                    it[end] = treapSetOf(rootId)
                }
            },
            procedures = first.procedures + second.procedures,
            symbolTable = first.symbolTable.merge(second.symbolTable)
        )
    }

    infix fun CoreTACProgram.andThen(other: CoreTACProgram) = mergeCodes(this, other)

    infix fun <T: TACCmd.Spec> CommandWithRequiredDecls<T>.andThen(next: TACExpr) = TACExprWithRequiredCmdsAndDecls<T>(
        exp = next,
        cmdsToAdd = this.cmds,
        declsToAdd = this.varDecls
    )

    /**
     * Sequentially compose two [CommandWithRequiredDecls] with a common supertype [T].
     */
    infix fun <T: TACCmd, U: T, V: T> CommandWithRequiredDecls<U>.andThen(other: CommandWithRequiredDecls<V>) = this.merge(other)

    infix fun <T: TACCmd, U: T, V: T> CommandWithRequiredDecls<U>.andThen(other: V) = this.merge(other)

    /**
     * Sequentially composed a sequence of commands of subtype of type [T] with the composable program [ProgType] held in a
     * container [other] of type [Comp].
     */
    infix fun <T: TACCmd, ProgType: ComposableProgram<ProgType, T>, Comp: WithProgram<ProgType, Comp>, V: T> CommandWithRequiredDecls<V>.andThen(other: Comp) = other.mapProgram {
        it.prependToBlock0(this)
    }

    /**
     * Sequentially compose the program of type [ProgType] held in the container [Comp] with the sequence of commands of
     * subtype of [T].
     */
    infix fun <T: TACCmd, ProgType: ComposableProgram<ProgType, T>, Comp: WithProgram<ProgType, Comp>, V: T> Comp.andThen(other: CommandWithRequiredDecls<V>) = this.mapProgram { prog: ProgType ->
        prog.appendToSinks(other)
    }

    infix fun <T: TACCmd, V: CommandWithRequiredDecls<T>> T.andThen(v: V) = v.copy(
        cmds = listOf(this) + v.cmds
    )

    /**
     * Prepend `this` onto the commands held in [other].
     */
    infix fun <T: TACCmd.Spec, U :T, V :T> CommandWithRequiredDecls<U>.andThen(other: TACExprWithRequiredCmdsAndDecls<V>) = TACExprWithRequiredCmdsAndDecls(
        exp = other.exp,
        cmdsToAdd = this.cmds + other.cmdsToAdd,
        declsToAdd = this.varDecls + other.declsToAdd
    )

    fun <T: TACCmd.Simple> CommandWithRequiredDecls<T>.toCore(name: String, rootId: NBId = StartBlock) = codeFromCommandWithVarDecls(
        name = name,
        rootId = rootId,
        cmdsWithDecls = this
    )

    fun <T: TACCmd> List<CommandWithRequiredDecls<T>>.flatten() = CommandWithRequiredDecls.mergeMany(this)
}

/** Another apt name would be "sequentialComposition" (for [CoreTACProgram]s) */
fun mergeCodes(first : CVLTACProgram, second : CVLTACProgram) : CVLTACProgram {
    // if one of the maps is empty, just return the other one
    if (second.isEmptyCode()) {
        return first
    } else if (first.isEmptyCode()) {
        return second
    }
    check(!first.code.keys.containsAny(second.code.keys))
    { "BlockId's may not intersect when using mergeCodes. (args: \"$first\", \"$second\")" }

    // connect leaves of first to root of second
    val leavesFirst = getLeaves(first)
    val rootSecond = second.entryBlockId

    val blocks = first.code + second.code
    val graph = MutableBlockGraph(first.blockgraph + second.blockgraph)
    val procedures = first.procedures + second.procedures
    leavesFirst.forEach { leaf ->
        graph[leaf] = graph[leaf].orEmpty() + rootSecond
    }

    val leavesSecond = getLeaves(second)
    val symbolTable = try {
        first.symbolTable.merge(second.symbolTable)
    } catch (e: TACSymbolTableException) {
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(first, ReportTypes.ERROR, StaticArtifactLocation.Reports, DumpTime.AGNOSTIC)
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(second, ReportTypes.ERROR, StaticArtifactLocation.Reports, DumpTime.AGNOSTIC)
        throw IllegalStateException("Got a type scope exception when merging ${first.name} and ${second.name}.", e)
    }

    val merged = first
        .copy(code = blocks,
            blockgraph = graph,
            symbolTable = symbolTable,
            procedures = procedures)
    logger.info{"Connecting leaves of ${first.name} $leavesFirst to root of ${second.name} $rootSecond"}
    logger.info{"New leaves should be $leavesSecond, got ${getLeaves(
        merged
    )}"}
    // TODO: Exception if not equal?

    return merged
}

/** Another apt name would be "parallelCompositionWithCondition" (for [CoreTACProgram]s) */
fun <T: TACCmd, U> mergeIfCodes(
    condCode: U,
    jumpiCmd: TACCmd.Simple.JumpiCmd,
    thenCode: U,
    elseCode: U
): U where U: TACProgram<T>, U: IProcedural{
    val leavesCond = getLeaves(condCode)
    val thenRoot = thenCode.entryBlockId
    val elseRoot = elseCode.entryBlockId

    logger.info{"Merging leaves of condition code $leavesCond to then root and else root $thenRoot, $elseRoot"}
    // TODO: Check that no intersection of block IDs
    val blocks = mutableMapOf<NBId,List<T>>()
    blocks.putAll(condCode.code)
    blocks.putAll(thenCode.code)
    blocks.putAll(elseCode.code)

    val graph = MutableBlockGraph(condCode.blockgraph + thenCode.blockgraph + elseCode.blockgraph)
    @Suppress("DEPRECATION") val symbolTable = condCode.symbolTable.merge(thenCode.symbolTable).merge(elseCode.symbolTable).mergeDecls(TACUtils.tagsFromCommand(jumpiCmd))
    val procedures = condCode.procedures + thenCode.procedures + elseCode.procedures

    leavesCond.single().let { leaf ->
        graph[leaf] = graph[leaf].orEmpty() + thenRoot + elseRoot
        @Suppress("UNCHECKED_CAST")
        blocks[leaf] = blocks[leaf]!!.plus(jumpiCmd as T)
    }

    return condCode.copyWith(
        code = blocks.uncheckedAs(),
        blockgraph = graph,
        symbolTable = symbolTable,
        procedures = procedures
    ).uncheckedAs()
}

fun codeFromCommandWithVarDecls(
    cmdsWithDecls: CommandWithRequiredDecls<TACCmd.Spec>,
    env: CVLCompiler.CallIdContext
) = codeFromCommandWithVarDecls(env.newBlockId(), cmdsWithDecls, "")

/**
 * @param name of the resulting CoreTACProgram
 * @param cmds the set of commands that will comprise the resulting CoreTACProgram
 * @param rootId the NBId for the basic block containing the list of commands
 */
@Suppress("DEPRECATION")
internal fun codeFromListOfCommands(
    rootId: NBId,
    cmds: List<TACCmd.Simple>,
    name: String
) = codeFromListOfCommandsWithTypeInfo(
    rootId,
    cmds,
    name,
    TACSymbolTable.withTags(TACUtils.tagsFromList(cmds))
)


internal fun codeFromCommandWithVarDecls(
    rootId: NBId,
    cmdsWithDecls: CommandWithRequiredDecls<TACCmd.Spec>,
    name: String
) =  CVLTACProgram(
    listOf(Pair(rootId, cmdsWithDecls.cmds.takeUnless { it.isEmpty() } ?: listOf(TACCmd.Simple.NopCmd))).toMap(),
    BlockGraph(rootId to treapSetOf()),
    name,
    TACSymbolTable.withRequiredDecls(cmdsWithDecls),
    IProcedural.empty()
) { UfAxioms.empty() }

internal fun codeFromCommandWithVarDecls(
    rootId: NBId,
    cmdsWithDecls: CommandWithRequiredDecls<TACCmd.Simple>,
    name: String
) =  CoreTACProgram(
    listOf(Pair(rootId, cmdsWithDecls.cmds.takeUnless { it.isEmpty() } ?: listOf(TACCmd.Simple.NopCmd))).toMap(),
    BlockGraph(rootId to treapSetOf()),
    name,
    TACSymbolTable.withRequiredDecls(cmdsWithDecls),
    UfAxioms.empty(),
    IProcedural.empty()
)

internal fun codeFromListOfCommandsWithTypeInfo(
    rootId: NBId,
    cmds: List<TACCmd.Spec>,
    name: String,
    symbolTable: TACSymbolTable
): CVLTACProgram {
    @Suppress("DEPRECATION")
    return CVLTACProgram(
        listOf(Pair(rootId, cmds.takeUnless { it.isEmpty() } ?: listOf(TACCmd.Simple.NopCmd))).toMap(),
        BlockGraph(rootId to treapSetOf()),
        name,
        symbolTable.mergeDecls(TACUtils.tagsFromList(cmds)),
        IProcedural.empty()
    ) { UfAxioms.empty() }
}
