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

import algorithms.findRoots
import algorithms.SimpleDominanceAnalysis
import algorithms.topologicalOrder
import algorithms.transitiveClosure
import allocator.Allocator
import allocator.SuppressRemapWarning
import analysis.*
import analysis.dataflow.*
import analysis.EthereumVariables.getStorageInternal
import analysis.icfg.SummaryStack
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFuncExitAnnotation
import analysis.worklist.NaturalBlockScheduler
import analysis.worklist.StepResult
import analysis.worklist.VisitingWorklistIteration
import cache.utils.ObjectSerialization
import cache.utils.TACWithAllocator
import com.certora.collect.*
import config.Config
import config.OUTPUT_NAME_DELIMITER
import config.ReportTypes
import datastructures.*
import datastructures.stdcollections.*
import decompiler.BMCRunner
import instrumentation.transformers.CodeRemapper
import kotlin.contracts.contract
import kotlin.contracts.ExperimentalContracts
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.SerialName
import log.*
import normalizer.CanonicalTranslationTable
import normalizer.forEachAxiom
import parallel.ParallelPool
import report.calltrace.CVLReportLabel
import report.dumps.DumpGraphHTML
import report.dumps.DumpGraphHTML.generateHTML
import report.dumps.generateCodeMap
import scene.IScene
import scene.NamedCode
import smt.CoverageInfoEnum
import spec.converters.repr.WithCVLProgram
import spec.cvlast.typedescriptors.IProgramOutput
import spec.CVLCompiler
import spec.CVLKeywords
import spec.toVar
import statistics.GeneralKeyValueStats
import statistics.IStatsJson
import statistics.IStatsJson.Companion.collect
import statistics.IStatsJson.Companion.totalMs
import statistics.SDCollectorFactory
import statistics.StatsValue
import statistics.toSDFeatureKey
import tac.*
import tac.TACBasicMeta.IS_IMMUTABLE
import testing.TacPipelineDebuggers.twoStateInvariant
import utils.*
import vc.data.parser.DeserializeTacStream
import vc.data.parser.ReadableTACJson
import vc.data.parser.serializeTAC
import vc.data.tacexprutil.getFreeVars
import vc.data.TACMeta.CONTRACT_ADDR_KEY
import vc.data.TACMeta.CVL_LABEL_END
import vc.data.TACMeta.CVL_LABEL_START
import vc.data.TACMeta.CVL_LABEL_START_ID
import vc.data.TACMeta.NO_CALLINDEX
import vc.data.TACMeta.ORIGINAL_STORAGE_KEY
import vc.data.TACMeta.ORIGINAL_TRANSIENT_STORAGE_KEY
import vc.data.TACMeta.STORAGE_KEY
import vc.data.TACMeta.TRANSIENT_STORAGE_KEY
import verifier.BlockMerger
import verifier.ContractUtils
import verifier.CoreToCoreTransformer
import java.io.*
import java.math.BigInteger
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream


private val logger = Logger(LoggerTypes.COMMON)
private val loggerCache = Logger(LoggerTypes.CACHE)
private val loggerSlave =
    Logger(LoggerTypes.SLAVE) // TACProgram is usually called from other places and ideally we'd like to report those debugs on the same topics
private val loggerTimes = Logger(LoggerTypes.TIMES)

typealias BlockNodes<T> = Map<NBId, List<T>>
typealias MutableBlockNodes<T> = MutableMap<NBId, MutableList<T>>

// Many of our graph algorithms are faster when applied to LinkedArrayHashMap, so we enforce that block graphs must be
// of that type.  Also, the successor sets are very small (one or two elements each), so we use `TreapSet` here because
// it has much less heap space overhead for small sets.
typealias BlockGraph = LinkedArrayHashMapReader<NBId, TreapSet<NBId>>
typealias MutableBlockGraph = LinkedArrayHashMap<NBId, TreapSet<NBId>>
fun BlockGraph(vararg entries: Pair<NBId, TreapSet<NBId>>): BlockGraph = entries.toMap(LinkedArrayHashMap<NBId, TreapSet<NBId>>())

class TACStructureException(msg: String) : Exception(msg)

@OptIn(ExperimentalContracts::class)
inline fun tacStructureInvariant(b: Boolean, f: () -> String) {
    contract {
        returns() implies b
    }
    if (!b) {
        throw TACStructureException(f())
    }
}


/**
 * A base class for TAC programs.  A TAC Program is a rooted directed acyclic graph of blocks; each block is
 * a sequence of [TACCmd]s.  The root of the graph is referred to as the entry point.
 *
 * A [TACProgram] also contains other metadata about the program.
 *
 * See [CoreTACProgram] for the commonly used class.
 *
 * @param T is the type of TACCmd held in the blocks
 */
abstract class TACProgram<T : TACCmd>(entry: NBId? = null) : NamedCode<ReportTypes> {
    /**
     * Contains type information for the symbols used in the TAC program. In particular:
     * - Variables to tags
     * - User defined types
     * - Uninterpreted function declarations
     */
    abstract val symbolTable: TACSymbolTable

    /**
     * A map from node IDs [NBId]s to list of commands. Can be any subtype of [T]
     */
    abstract val code: BlockNodes<T>



    /**
     * A map from node IDs [NBId]s to set of successor [NBId]s.
     */
    abstract val blockgraph: BlockGraph

    /**
     * A name for the TAC program, for debuggability.
     */
    abstract val name: String

    abstract val analysisCache: IAnalysisCache?

    protected val entryBlockIdInternal: NBId? by lazy {
        entry ?: run {
            if (blockgraph.isEmpty()) {
                null
            } else {
                val roots = blockgraph.keys.toMutableSet()
                // remove all nodes that are the successor of some other node
                // the remaining nodes will be the set of roots
                blockgraph.forEachEntry { (_, succs) ->
                    roots.removeAll(succs)
                }
                if (roots.size != 1) {
                    error("Graph with not exactly 1 root: roots=$roots, this=$this")
                }
                roots.first()
            }
        }
    }

    /**
     * The entrypoint to the program found by traversing the [BlockGraph] and yielding the node with no incoming edges.
     * @throws IllegalStateException if the node cannot be initialized
     */
    val entryBlockId: NBId
        get() =
            entryBlockIdInternal
                ?: throw IllegalStateException("Attempting to retrieve head of ill-formed program $name. BlockGraph=$blockgraph, this=$this")

    /**
     * A checker function for checking the consistency of [code] and [blockgraph] contents. Used to ensure validity
     * of constructed TAC programs.
     */
    fun checkCodeGraphConsistency() {
        code.filterNot { (key, value) ->
            if (key !in blockgraph) {
                // every code block must appear in blockgraph
                false
            } else {
                when (val last = value.lastOrNull()) {
                    is TACCmd.Simple.JumpiCmd -> {
                        // if a block ends in a conditional jump, the number of successors has to be 2 if the
                        // then and else cases are leading to different blocks, and 1 if the then and else cases lead
                        // to the same block.
                        // To be more precise, the successor set of the block in the blockgraph must agree with the set
                        // implied by the conditional jump.
                        blockgraph[key] == setOf(last.dst, last.elseDst)
                    }
                    is TACCmd.Simple.JumpCmd -> {
                        // if a block ends in a regular jump command, the successor set must be the same as specified
                        // by the jump instruction
                        blockgraph[key] == setOf(last.dst) && last.dst in blockgraph
                    }
                    is TACCmd.Simple.SummaryCmd -> if (last.summ is ConditionalBlockSummary) {
                        // if a block ends in a summary command, and it's a conditional block summary specifying
                        // successors, those must agree with the successors of the block in the blockgraph
                        blockgraph[key] == last.summ.successors
                    } else {
                        // if a block ends in a summary command which is not a conditional block summary, it must
                        // have at most 1 successor
                        blockgraph[key]!!.size <= 1
                    }
                    // in any other case, a block may have at most 1 successor
                    else -> blockgraph[key]!!.size <= 1
                }
            }
        }.let { nonConsistentEntries ->
            tacStructureInvariant(nonConsistentEntries.isEmpty()) {
                "Got entries in code not consistent with graph: ${
                    nonConsistentEntries.map {
                        it.key to (it.value.last() to blockgraph[it.key])
                    }
                }"
            }
        }
    }

    abstract fun getNodeCode(n: NBId): List<T>
    fun getNodeSucc(n: NBId): Set<NBId> {
        return blockgraph.let { g ->
            if (n in g) {
                g[n]!!
            } else {
                throw Exception("Node $g does not appear in graph $g")
            }
        }
    }

    abstract fun toPatchingProgram(): PatchingTACProgram<T>

    /**
     * This seems to be first accessed in the `toTacProgram` function here, from
     * `TACVerifier`. If accessed before in the flow, might trigger an error,
     * reflecting the fact that there is a cycle in [blockgraph], and thus, cannot compute
     * topological order.
     *
     * Beware that this is a topological order that goes from entry to exit of the graph, which is the reverse of what
     * our helper methods return. (`Fw` stands for "forward".)
     */
    val topoSortFw: List<NBId> by lazy {
        topologicalOrder(blockgraph).reversed()
    }

    fun logStats(header: String, logger: Logger) {
        logger.info {
            "$header: ${blockgraph.keys.size} blocks (nodes), ${
                blockgraph.values.fold(
                    0
                ) { A, e -> A + e.size }
            } edges"
        }
    }


    fun toFile(filename: String): String {
        val basename = ArtifactFileUtils.getBasenameOnly(filename)
        // TODO: Config.getNoOutput should not be enabled together with isUseCache
        if (ArtifactManagerFactory.isEnabled() && Config.isEnabledReport(basename)) {
            serializeTAC(this, filename)
        }
        return filename
    }


    private fun writeGraph(edges: Map<Edge, List<TACExpr>>, reportName: String) {
        if (ArtifactManagerFactory.isEnabled() && Config.isEnabledReport(reportName) && !Config.LowFootprint.get()) {
            val codeMap = generateCodeMap(this, reportName, edges)
            DumpGraphHTML.writeCodeToHTML(
                codeMap,
                ArtifactManagerFactory().fitFileLength(reportName, ".html")
            )
        }
    }

    override val defaultType: ReportTypes
        get() = ReportTypes.NONE

    override fun dump(dumpType: ReportTypes, where: String, time: DumpTime) {
        this.writeGraphWithAccompanyingTAC(dumpType, where, time)
    }

    private fun getEdgesDefault(): Map<Edge,List<TACExpr>> {
        // no path conditions
        val ret = mutableMapOf<Edge,List<TACExpr>>()
        blockgraph.map { (k, v) ->
            val handled = code[k]?.let { cmds ->
                cmds.lastOrNull()?.let { lastCmd ->
                    (lastCmd as? TACCmd.Simple.JumpiCmd)?.let { jumpi ->
                        val condSym = jumpi.cond.asSym()
                        ret[Edge(k,jumpi.dst)] = listOf(condSym)
                        ret[Edge(k,jumpi.elseDst)] = listOf(TACExpr.UnaryExp.LNot(condSym))
                        true
                    }
                }
            } ?: false

            if (!handled) {
                v.forEach { trg ->
                    ret[Edge(k,trg)] = emptyList()
                }
            }
        }
        return ret
    }
    fun writeToOutTAC(name: String) {
        val edges = getEdges()
        serializeTAC(this, "${name}.tac")
        val codeMap = generateCodeMap(this, name, edges)
        FileWriter("${name}.html").write(generateHTML(codeMap))
    }

    private fun getEdges(): Map<Edge, List<TACExpr>> {
        return if (this is CoreTACProgram) {
            try {
                TACCommandGraph(this).let { g ->
                    blockgraph.keys.flatMap { src ->
                        g.pathConditionsOf(src).map { e ->
                            val trg = e.key
                            val cond = e.value.getAsExpression()
                            Edge(src, trg) to listOf(cond)
                        }
                    }
                }.toMap()
            } catch (e: Exception) {
                // best effort mode until we clean up TAC further.
                // reason for exception is _probably_ the inexistence of elseDst for some jumpi cmd
                getEdgesDefault()
            }
        } else {
            getEdgesDefault()
        }
    }
    private fun writeGraphWithAccompanyingTAC(
        reportType: ReportTypes,
        outputDir: String,
        dumpTime: DumpTime
    ): String /* written filename */ {
        val edges = getEdges()
        return writeGraphWithAccompanyingTAC(reportType, edges, outputDir, dumpTime)
    }

    private fun writeGraphWithAccompanyingTAC(
        reportType: ReportTypes,
        edges: Map<Edge, List<TACExpr>>,
        outputDir: String,
        dumpTime: DumpTime
    ): String /* written filename */ {
        // write file and report
        if (ArtifactManagerFactory.isEnabled()) {
            // toFile should be outside the runnable task because we always want it to occur and it's also very fast
            val fullName = "${reportType.toFilenamePrefix()}${OUTPUT_NAME_DELIMITER}${dumpTime.reportSuffixWithDelimiter}${name}"
            val nameWithTacSuffix = ArtifactManagerFactory().fitFileLength("$fullName.tac", ".tac")
            val filepath = "${outputDir}${File.separator}$nameWithTacSuffix"
            if (Config.isEnabledReport(fullName) && !Config.LowFootprint.get()) {
                return this.toFile(filepath).apply {
                    writeGraph(edges, fullName)
                }
            }
        }
        return ""
    }

    fun getStartingBlock(): NBId = entryBlockId

    fun getEndingBlocks(): Set<NBId> {
        return blockgraph.filter { it.value.isEmpty() }.keys
    }

    fun isEmptyCode(): Boolean {
        // no code - no nodes
        return code.keys.isEmpty()
            // or, one block with just nop-s
            || (code.values.singleOrNull()
                ?.let { cmds -> cmds.all { it == TACCmd.Simple.NopCmd } } == true)
    }

    internal fun _addToBlock(
        l: List<T>,
        b: NBId,
        isBefore: Boolean,
        varDecls: Set<TACSymbol.Var> = setOf(),
        elab: (NBId) -> TACBlockGen<T, LTACCmdGen<T>>
    ): TACProgram<T> {
        val patch = toPatchingProgram()
        if (isBefore) {
            patch.addBefore(elab(b).commands.first().ptr, l)
        } else {
            val after = elab(b).commands.last()
            patch.replace(after.ptr) { _ -> listOf(after.cmd) + l }
        }
        patch.addVarDecls(varDecls)
        return patch.toCode(this)
    }

    internal fun _appendToSinks(cmds: CommandWithRequiredDecls<T>, sinks: List<LTACCmdGen<T>>): TACProgram<T> {
        val patch = toPatchingProgram()
        patch.addRequiredDecls(cmds)
        sinks.forEach { sink ->
            val new = listOf(sink.cmd) + cmds.cmds
            patch.replaceCommand(sink.ptr, new)
        }
        return patch.toCode(this)
    }

    internal fun _insertBeforeLastCommandOfSinks(cmds: CommandWithRequiredDecls<T>, sinks: List<LTACCmdGen<T>>): TACProgram<T> {
        val patch = toPatchingProgram()
        patch.addRequiredDecls(cmds)
        sinks.forEach { sink ->
            val new = cmds.cmds.plus(sink.cmd)
            patch.replaceCommand(sink.ptr, new)
        }
        return patch.toCode(this)
    }

    /**
        Traverse the code in DFS fashion from roots (as defined in [TACCommandGraph.roots]).

        It is important for this traversal to be the same from run to run, as this is used as part of canonicalization.
        We rely here on the fact that [blockgraph]'s values are `TreapSet<NBId>`, which are sorted, and so will always
        have the same ordering from run to run, given the same NBId values.
     **/
    fun dfs(visit: (NBId, List<T>) -> Unit) {
        val blockStack =
            analysisCache?.graph?.roots?.mapTo(mutableListOf()) { it.ptr.block }
                ?: findRoots(blockgraph).toMutableList()

        val visited = mutableSetOf<NBId>()

        while (blockStack.isNotEmpty()) {
            val nbid = blockStack.removeLast()
            val commands = code[nbid] ?: error("NBId $nbid is not in the graph")

            visit(nbid, commands)

            blockgraph[nbid]?.forEachElement { succ ->
                if (visited.add(succ)) {
                    blockStack.add(succ)
                }
            }
        }
    }
}

sealed interface WithUFAxioms {
    /**
     * A store of axioms for uninterpreted functions declared in [symbolTable]
     */
    val ufAxioms: UfAxioms

}

fun<E: ShouldErase> CanonicalTACProgram<TACCmd.Simple, E>.toCoreTACProgram(check: Boolean): CoreTACProgram =
    CoreTACProgram(
        code = code,
        blockgraph = blockgraph,
        name = name,
        symbolTable = symbolTable,
        instrumentationTAC = instrumentationTAC,
        procedures = procedures,
        check = check,
    )


sealed interface ShouldErase {
    object Erase: ShouldErase
    object Dont: ShouldErase
}

/**
 * A program that can be composed with a sequence of commands of type [T] (either prepending
 * or appending), producing an [Self]. It is expected (but not required) that [Self] is the same
 * type implementing the [ComposableProgram] interface.
 */
sealed interface ComposableProgram<out Self, in T: TACCmd> {
    fun <U: T> prependToBlock0(l: List<U>): Self

    fun <U: T> prependToBlock0(c: CommandWithRequiredDecls<U>): Self

    fun <U :T> appendToSinks(cmds: CommandWithRequiredDecls<U>): Self
}

/**
 * An object that carries with it a composable program of type [R], which can be updated
 * via [mapProgram]. Although not required, it is expected that [Self] is the same type
 * that is implementing this interface (hence the name).
 *
 * All programs that implement [ComposableProgram] by convention also implement this interface.
 */
interface WithProgram<R, out Self> {
    fun mapProgram(f: (R) -> R) : Self
}

/**
 * Transform this [CoreTACProgram] to its canonical representation
 * Please note that
 *  ```
 *  check = false
 *  ```
 *  since not all the vars in [code] appear in [TACSymbolTable.tags] for some freaky reason
 **/
@SuppressRemapWarning
data class CanonicalTACProgram<T : TACCmd.Spec, E : ShouldErase>(
    override val symbolTable: TACSymbolTable,
    override val blockgraph: BlockGraph,
    override val code: BlockNodes<T>,
    override val name: String,
    override val instrumentationTAC: InstrumentationTAC,
    override val procedures: Set<Procedure>,
) : TACProgram<T>(entry = null), IProcedural, InstrumentedTAC, WithUFAxioms {
    override val ufAxioms: UfAxioms
        get() = instrumentationTAC.ufAxioms

    override val analysisCache: IAnalysisCache?
        get() = null

    override fun myName(): String = name

    companion object {
        operator fun <T : TACCmd.Spec, E: ShouldErase, TAC> invoke(
            tac: TAC,
            shouldErase : E,
            initTable: CanonicalTranslationTable? = null,
        ): CanonicalTACProgram<T, E>
            where
            TAC : TACProgram<T>,
            TAC : InstrumentedTAC,
            TAC : IProcedural {
            val table = initTable ?: CanonicalTranslationTable(
                tac,
                loggerCache,
                shouldErase is ShouldErase.Erase
            ).also { it.show() }

            val newBlocks = tac.code.entries
                .map { (nbid, cmds) -> table.mapNBId(nbid) to cmds.map { table.mapSpec(it).uncheckedAs<T>() }}
                .sortedBy { it.first }
                .toMap(LinkedArrayHashMap())
            val newGraph = tac.blockgraph.entries
                .map { (nbid, nbids) -> table.mapNBId(nbid) to nbids.updateElements { table.mapNBId(it) }}
                .sortedBy { it.first }
                .toMap(MutableBlockGraph())

            // We allow procedures to have [callId] that do not appear in [code]
            // So it is not guaranteed that all call-index are in [code]
            // - if it's not there - it has no semantic meaning - and it is fine to filter it out
            val procedures = tac.procedures.mapNotNullToSet {
                table.mapCallIndex(it.callId)?.let { id -> it.copy(callId = id) }
            }

            val canonAxioms =
                tac.instrumentationTAC.ufAxioms.mapTo { it }.entries.associate { (uf, axioms) ->
                    table.mapUF(uf) to axioms.map { TACAxiom(table.mapExpr(it.exp)) }
                }
            val instrumentationTAC = tac.instrumentationTAC.copy(
                ufAxioms = UfAxioms(canonAxioms),
            )

            val userDefinedTypes = tac.symbolTable.userDefinedTypes
            val uninterpretedFunctions =
                tac.symbolTable.uninterpretedFunctions().mapToSet { table.mapUF(it) }

            val tags = Tags(
                newBlocks.values.asSequence()
                    .flatMap { cmds -> cmds.flatMap { it.getFreeVarsOfRhs() } + cmds.mapNotNull { it.getLhs() } }
                    .toSet() + canonAxioms.values.asSequence().flatten()
                    .flatMap { it.exp.getFreeVars() })
            val newTACSymbolTable =
                TACSymbolTable(userDefinedTypes, uninterpretedFunctions, tags, mapOf())

            return CanonicalTACProgram(
                code = newBlocks,
                blockgraph = newGraph,
                name = tac.name,
                symbolTable = newTACSymbolTable,
                instrumentationTAC = instrumentationTAC,
                procedures = procedures,
            )
        }
    }

    override fun getNodeCode(n: NBId): List<T> = code.get(key = n)!!

    override fun toPatchingProgram(): PatchingTACProgram<T> =
        PatchingTACProgram(code, blockgraph, name, entryBlockIdInternal)

    override fun dumpBinary(where: String, label: String): TACFile =
        error("dumpBinary Not implemented for CanonicalTACProgram please convert it to CoreTACProgram")
}


/**
 * get a hash code of a canonical representation of [CoreTACProgram]
 * Based on the implementation of [CoreTACProgram.toFile]
 * Useful for caching [CoreTACProgram], among other things
 **/
fun<T: TACCmd.Spec> CanonicalTACProgram<T, ShouldErase.Erase>.digest(): String {
    val dos =
        DigestOutputStream(OutputStream.nullOutputStream(), MessageDigest.getInstance("MD5"))
    ObjectOutputStream(dos).use { writeIntoObjectStream(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(dos.messageDigest.digest())
}

/**
 * write this [CoreTACProgram] in a [ObjectOutputStream]
 * while making sure the ordering of different collections is preserved
 *
 * [CERT-1717](https://certora.atlassian.net/browse/CERT-1717)
 * [CERT-2092](https://certora.atlassian.net/browse/CERT-2092)
 **/
fun <T : TACCmd.Spec, TAC> TAC.writeIntoObjectStream(objectStream: ObjectOutputStream)
    where TAC : TACProgram<T>, TAC : InstrumentedTAC, TAC : IProcedural {
    val dataEncoder = object : DataOutputEncoder(objectStream, ReadableTACJson.serializersModule) {
        // log all the digested values
        override fun <T : Any?> encodeSerializableValue(
            serializer: SerializationStrategy<T>,
            value: T
        ) {
            logger.trace { "$value" }
            super.encodeSerializableValue(serializer, value)
        }
    }

    dataEncoder.encodeSerializableValue(
        ListSerializer(Procedure.serializer()),
        procedures.sortedBy { it.callId })

    // Please note we don't include symbolTable.userDefinedTypes in the digest -
    // if it's relevant it will appear in `code`

    dataEncoder.encodeSerializableValue(
        ListSerializer(FunctionInScope.UF.serializer()),
        symbolTable.uninterpretedFunctions().sortedBy { it.name })

    forEachAxiom(keyComparator = { it.name }) { uf, axioms ->
        dataEncoder.encodeSerializableValue(FunctionInScope.UF.serializer(), uf)
        dataEncoder.encodeSerializableValue(ListSerializer(TACAxiom.serializer()), axioms)
    }

    dataEncoder.encodeSerializableValue(
        MapSerializer(NBId.serializer(), SetSerializer(NBId.serializer())),
        blockgraph.toSortedMap()
    )

    dataEncoder.encodeSerializableValue(
        MapSerializer(NBId.serializer(), ListSerializer(TACCmd.Spec.serializer())),
        code.toSortedMap()
    )
}

data class EVMTACProgram(
    override val code: BlockNodes<TACCmd>,
    override val blockgraph: BlockGraph,
    override val name: String,
    override val symbolTable: TACSymbolTable,
    val entryBlock: NBId? = null,
) : TACProgram<TACCmd>(entryBlock) {
    init {
        try {
            checkCodeGraphConsistency()
        } catch (e: Exception) {
            when (e) {
                is TACTypeCheckerException, is TACStructureException -> {
                    ArtifactManagerFactory().dumpMandatoryCodeArtifacts(this, ReportTypes.ERROR, StaticArtifactLocation.Reports, DumpTime.AGNOSTIC)
                }
            }
            throw e
        }
    }

    override fun myName(): String = name

    override val analysisCache: IAnalysisCache?
        get() = null

    override fun getNodeCode(n: NBId): List<TACCmd> {
        return code.let { g ->
            if (n in g) {
                g[n]!!
            } else {
                throw Exception("Node $g does not appear in graph $g")
            }
        }
    }

    override fun toPatchingProgram(): PatchingTACProgram<TACCmd> =
        PatchingTACProgram(code, blockgraph, name, entryBlockIdInternal)

    override fun dumpBinary(where: String, label: String): TACFile {
        throw UnsupportedOperationException("Can only dump Simple TAC programs to binary")
    }

    /**
     * Performs an unsophisticated conversion of an [EVMTACProgram] to a [CoreTACProgram] by first trying to see if
     * all commands are already [TACCmd.Simple].
     * Invokes [ContractUtils.transformEVMToSimple] if not.
     */
    fun convertToSimple(): CoreTACProgram {
        return if (code.values.all { blockCmds ->
                    blockCmds.all { cmd ->
                        cmd is TACCmd.Simple
                    }
                }) {
            val newCode = code.mapValues { blockCmds ->
                blockCmds.value.map { cmd ->
                    cmd as TACCmd.Simple
                }
            }

            CoreTACProgram(
                newCode,
                blockgraph,
                name,
                symbolTable,
                InstrumentationTAC(UfAxioms.empty()),
                IProcedural.empty(),
                entryBlock = entryBlockIdInternal
            )
        } else {
            ContractUtils.transformEVMToSimple(this)
        }
    }
}

@SuppressRemapWarning
data class CVLTACProgram(
    override val code: BlockNodes<TACCmd.Spec>,
    override val blockgraph: BlockGraph,
    override val name: String,
    override val symbolTable: TACSymbolTable,
    override val procedures: Set<Procedure>,
    val ufAxiomBuilder: () -> UfAxioms
) : TACProgram<TACCmd.Spec>(null), IProcedural, Serializable, IProgramOutput, WithCVLProgram<CVLTACProgram>, ComposableProgram<CVLTACProgram, TACCmd.Spec> {
    override fun mapCVLProgram(f: (CVLTACProgram) -> CVLTACProgram): CVLTACProgram {
        return f(this)
    }

    override val analysisCache: IAnalysisCache?
        get() = null

    override fun getNodeCode(n: NBId): List<TACCmd.Spec> {
        return code.get(key = n)!!
    }

    override fun toPatchingProgram(): PatchingTACProgram<TACCmd.Spec> =
        PatchingTACProgram(code, blockgraph, /* procedures?*/ name, entryBlockIdInternal)

    override fun dumpBinary(where: String, label: String): TACFile {
        error("Not yet implemented")
    }

    override fun myName(): String = name

    val commandGraph by lazy {
        CVLTACCommandGraph(blockgraph, code, symbolTable)
    }

    fun patching(function: CVLTACProgram.(PatchingTACProgram<TACCmd.Spec>) -> Unit): CVLTACProgram {
        val p = toPatchingProgram()
        this.function(p)
        return p.toCode(this, TACCmd.Simple.NopCmd)
    }

    /**
     * TODO: Pretty much anywhere this is called as of this commit should probably get some attention.
     */
    fun transformToCore(scene: IScene): CoreTACProgram = CVLToSimpleCompiler(scene).compile(this)

    // todo deal with warnings after we figure out if need remappings here
    /*override*/ fun dumpBinary(@Suppress("UNUSED_PARAMETER") where: String): TACFile {
        throw UnsupportedOperationException()
    }

    fun updateEdge(s: NBId, targets: TreapSet<NBId>): CVLTACProgram {
        val newGraph = MutableBlockGraph(blockgraph)
        newGraph.put(s, targets)

        return this.copy(blockgraph = newGraph)
    }

    companion object {
        fun empty(name: String) = CVLTACProgram(
            mapOf(),
            MutableBlockGraph(),
            name,
            TACSymbolTable.empty(),
            IProcedural.empty()
        ) { UfAxioms.empty() }
    }

    /**
     * Adds a sink with the new sink node using a [CVLCompiler.BaseCompilationEnvironment] to determine the block ID,
     * in particular the callId field of the new lbock
     * @param cmds - a [CommandwithRequiredDecls]
     * @param env - an environment helping determine the new block ID
     */
    fun addSink(cmds: CommandWithRequiredDecls<TACCmd.Spec>, env: CVLCompiler.CallIdContext): Pair<CVLTACProgram, NBId> {
        val newBlockId = env.newBlockId()
        val newCode = code.plus(Pair(newBlockId, cmds.cmds))
        val newDecls = cmds.varDecls.toSet()
        val newGraph = MutableBlockGraph(blockgraph)
        getEndingBlocks().forEach {
            newGraph.put(it, treapSetOf(newBlockId))
        }
        newGraph.put(newBlockId, treapSetOf())

        return this.copy(
            code = newCode,
            blockgraph = newGraph,
            symbolTable = this.symbolTable.mergeDecls(Tags(newDecls))
        ) to newBlockId
    }

    private fun addToBlock(
            l: List<TACCmd.Spec>,
            b: NBId,
            isBefore: Boolean,
            varDecls: Set<TACSymbol.Var> = setOf(),
    ): CVLTACProgram = _addToBlock(l, b, isBefore, varDecls) { blockIdentifier -> commandGraph.elab(blockIdentifier) } as CVLTACProgram

    override fun <U: TACCmd.Spec> prependToBlock0(l: List<U>): CVLTACProgram {
        val startBlock = getStartingBlock()
        return addToBlock(l, startBlock, true)
    }

    override fun <U: TACCmd.Spec> prependToBlock0(c: CommandWithRequiredDecls<U>): CVLTACProgram =
        addToBlock(c.cmds, getStartingBlock(), true, c.varDecls)

    override fun <U: TACCmd.Spec> appendToSinks(cmds: CommandWithRequiredDecls<U>): CVLTACProgram =
            _appendToSinks(cmds, commandGraph.sinks) as CVLTACProgram

    fun insertBeforeLastCommandOfSinks(cmds: CommandWithRequiredDecls<TACCmd.Spec>): CVLTACProgram =
        _insertBeforeLastCommandOfSinks(cmds, commandGraph.sinks) as CVLTACProgram

    fun parallelLtacStream(): Stream<GenericLTACCmd<TACCmd.Spec>> = code.entries.parallelStream().flatMap { (block, cmds) ->
        cmds.mapIndexed { pos, cmd ->
            GenericLTACCmd<TACCmd.Spec>(
                    cmd = cmd,
                    ptr = CmdPointer(block = block, pos = pos)
            )
        }.stream()
    }

    fun ltacStream(): Stream<GenericLTACCmd<TACCmd.Spec>> = parallelLtacStream().sequential()

    fun mergeBlocks(): CVLTACProgram {
        return BlockMerger.mergeBlocks(this) { c, code, graph ->
            c.copy(
                code = code, blockgraph = graph
            )
        }
    }

    fun prependCodeToCodeBlock(entry: NBId, codeToPrepend: CommandWithRequiredDecls<TACCmd.Spec>): CVLTACProgram {
        return addToBlock(
            codeToPrepend.cmds,
            entry,
            true,
            codeToPrepend.varDecls,
        )
    }

    fun wrapWith(start: CommandWithRequiredDecls<TACCmd.Spec>, end: CommandWithRequiredDecls<TACCmd.Spec>): CVLTACProgram {
        return this.prependToBlock0(start).appendToSinks(end)
    }

    fun wrapWith(start: TACCmd.Spec, end: TACCmd.Spec): CVLTACProgram {
        return this.wrapWith(CommandWithRequiredDecls(start), CommandWithRequiredDecls(end))
    }

    fun wrapWithCVLLabelCmds(label: CVLReportLabel): CVLTACProgram {
        val labelId = Allocator.getFreshId(Allocator.Id.CVL_EVENT)
        val start = TACCmd.Simple.AnnotationCmd(CVL_LABEL_START, label).plusMeta(CVL_LABEL_START_ID, labelId)
        val end = TACCmd.Simple.AnnotationCmd(CVL_LABEL_END, labelId)
        return this.wrapWith(start, end)
    }
}


class CoreTACProgram private constructor(
    _code: BlockNodes<TACCmd.Simple>,
    override val blockgraph: BlockGraph,
    override val name: String,
    override val symbolTable: TACSymbolTable,
    override val instrumentationTAC: InstrumentationTAC,
    override val procedures: Set<Procedure>,
    val check: Boolean = true, // TODO: REMOVE THIS AFTER REMOVING IRTOTACCONVERTER
    entryBlock: NBId? = null,
) : ICoreTACProgram, TACProgram<TACCmd.Simple>(entryBlock), IProcedural, InstrumentedTAC, WithUFAxioms,
    Serializable, ComposableProgram<CoreTACProgram, TACCmd.Simple>, WithProgram<CoreTACProgram, CoreTACProgram> {

    override fun mapProgram(f: (CoreTACProgram) -> CoreTACProgram): CoreTACProgram {
        return f(this)
    }

    // Protection against OOM during processing of this program.
    init {
        val blockCount = _code.size
        if (blockCount > Config.MaxBlockCount.get()) {
            throw CertoraException(
                CertoraErrorType.CODE_SIZE,
                "$name expanded to too many basic blocks: $blockCount > ${Config.MaxBlockCount.get()}. You can configure ${Config.MaxBlockCount.name} accordingly"
            )
        }
        val commandCount = _code.values.sumOf { it.size }
        if (commandCount > Config.MaxCommandCount.get()) {
            throw CertoraException(
                CertoraErrorType.CODE_SIZE,
                "$name expanded to too many commands: $commandCount > ${Config.MaxCommandCount.get()}. You can configure ${Config.MaxCommandCount.name} accordingly"
            )
        }
    }

    override val code: BlockNodes<TACCmd.Simple> = _code.toPreferredMap()

    fun copy(
        code: BlockNodes<TACCmd.Simple> = this.code,
        blockgraph: BlockGraph = this.blockgraph,
        name: String = this.name,
        symbolTable: TACSymbolTable = this.symbolTable,
        instrumentationTAC: InstrumentationTAC = this.instrumentationTAC,
        procedures: Set<Procedure> = this.procedures,
        check: Boolean = this.check, // TODO: REMOVE THIS AFTER REMOVING IRTOTACCONVERTER
        entryBlock: NBId? = null
    ) = invoke(
        code,
        blockgraph,
        name,
        symbolTable,
        instrumentationTAC,
        procedures,
        check,
        entryBlock
    )

    override val ufAxioms: UfAxioms
        get() = instrumentationTAC.ufAxioms

    override fun myName(): String = name

    class Linear(var ref: CoreTACProgram) {
        fun copy(
            code: BlockNodes<TACCmd.Simple> = this.ref.code,
            blockGraph: BlockGraph = this.ref.blockgraph,
            name: String = this.ref.name,
            symbolTable: TACSymbolTable = this.ref.symbolTable,
            instrumentationTAC: InstrumentationTAC = this.ref.instrumentationTAC,
            procedures: Set<Procedure> = this.ref.procedures,
            check: Boolean = this.ref.check
        ) {
            ref = this.ref.copy(
                code, blockGraph, name, symbolTable, instrumentationTAC, procedures, check
            )
        }

        fun map(transformer: CoreToCoreTransformer): Linear {
            if (ParallelPool.isPoolCancelled()) {
                Thread.currentThread().interrupt()
                throw InterruptedException()
            }
            this.ref = transformer.applyTransformer(this.ref)
            return this
        }

        fun mapIf(cond: Boolean, transformer: CoreToCoreTransformer): Linear {
            return if (cond) {
                map(transformer)
            } else {
                this
            }
        }

        /**
         * (Jaroslav) Might disable (skip) the transformer. Currently, we use this function only to skip transformers
         * that make the unsat core analysis imprecise (i.e. when the flag `-numOfUnsatCores=<1+>` is used).
         * Moreover, we currently do this by checking the ReportType of the transformer, i.e., considering the
         * reportType to be an identifier of the transformer.
         * T.O.D.O.: a better solution would be to have unique names for individual transformers (forming and enum?)
         * and maintain Config.DisallowedTransformers field to control the transformers. However, that would require a
         * bigger refactoring of the code.
         */
        fun mapIfAllowed(transformer: CoreToCoreTransformer): Linear {
            val disallowedTransformersByReportType = mutableListOf<ReportTypes>()

            //these make the unsat core analysis imprecise (the list might not be exhaustive)
            if(Config.CoverageInfoMode.get() == CoverageInfoEnum.ADVANCED) {
                disallowedTransformersByReportType.addAll(listOf(
                    ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS1,
                    ReportTypes.TERNARY_OPTIMIZE,
                    ReportTypes.OPTIMIZE_PROPAGATE_CONSTANTS2,
                    ReportTypes.OPTIMIZE_INFEASIBLE_PATHS,
                    ReportTypes.INTERVALS_OPTIMIZE,
                    ReportTypes.GLOBAL_INLINER,
                    ReportTypes.OPTIMIZE_OVERFLOW
                ))
            }

            Config.DisabledTransformations.get().map {it.lowercase()}.let { disabledTransformsLowercase ->
                ReportTypes.byLowerCaseName.filter { reportEntry ->
                    reportEntry.key in disabledTransformsLowercase
                }.values.let { reportTypesToDisable ->
                    disallowedTransformersByReportType.addAll(reportTypesToDisable)
                }
            }

            return if (transformer.reportType in disallowedTransformersByReportType) {
                this
            } else {
                    map(transformer)
            }
        }

    }

    override fun dumpBinary(where: String, label: String): TACFile {
        try {
            val filename = "${where}${File.separator}${label}${
                if (label.isNotEmpty()) {
                    "-"
                } else {
                    ""
                }
            }${name.truncateString()}.tacbin"
            ObjectSerialization.writeObject(
                TACWithAllocator(this, Allocator.saveState()),
                filename
            )
            return TACFile(
                this.name,
                filename,
                true
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to dump TAC object to a binary file at $where"
            )
            throw e
        }
    }

    private class DigestStats(override val stats: MutableMap<Key, StatsValue>) :
        IStatsJson<DigestStats.Key>() {
        constructor(vararg s: Pair<Key, StatsValue>) : this(s.associate { it }.toMutableMap())

        @KSerializable
        enum class Key {
            @SerialName("tacName")
            TacName,

            @SerialName("writeToStreamMs")
            WriteToStreamMs,

            @SerialName("canonicalizationMs")
            CanonicalizationMs,

            @SerialName("numCmds")
            NumCmds,

            @SerialName("totalMs")
            TotalMs
        }

        override val keySerializer: KSerializer<Key>
            get() = Key.serializer()
        override val totalMsKey: Key
            get() = Key.TotalMs
        override val collectKey: String = ReportTypes.DIGEST.toString()

    }

    fun toCanonical(): CoreTACProgram = run {
        val newProg = CanonicalTACProgram(this, ShouldErase.Dont).toCoreTACProgram(check)
        twoStateInvariant(this, newProg, ReportTypes.CANONICALIZATION)
        newProg
    }

    /**
     * get a hash code of a canonical representation of [CoreTACProgram]
     * Based on the implementation of [CoreTACProgram.toFile]
     * Useful for caching [CoreTACProgram], among other things
     **/
    fun digest(): String {
        loggerCache.trace { "START DIGEST $name" }
        val stats = DigestStats(
            DigestStats.Key.TacName to StatsValue.Str(name),
            DigestStats.Key.NumCmds to StatsValue.Count(
                code.values.asSequence().map { it.size }.sum()
            )
        )
        val canonicalTAC =
            stats.measure(DigestStats.Key.CanonicalizationMs) { CanonicalTACProgram(this, ShouldErase.Erase) }
        val digest = stats.measure(DigestStats.Key.WriteToStreamMs) { canonicalTAC.digest() }
        stats.totalMs().collect()
        loggerCache.trace { "END DIGEST $name" }
        return digest
    }

    private fun checkCodeStructure() {

        // check all nodes have commands
        tacStructureInvariant(code.all {
            it.value.isNotEmpty()
        }) {
            "Cannot create a program with empty blocks: ${
                code.filter { it.value.isEmpty() }
            }"
        }

        // check there's exactly one root
        tacStructureInvariant(findRoots(blockgraph).size <= 1) {
            "Cannot create a TAC program with multiple roots: ${analysisCache.graph.roots.map { it.ptr }} in ${analysisCache.graph.blockSucc}"
        }

        // check consistency blocks/graph
        tacStructureInvariant(code.size == blockgraph.size) { "Code blocks and graph should have the same nodes" }
        tacStructureInvariant(code.keys.containsAll(blockgraph.keys)) {
            "Code blocks do not contain ${
                code.keys.minus(
                    blockgraph.keys
                )
            }"
        }
        checkCodeGraphConsistency()

        // check no jump commands in the middle of a block
        // and check that no jumpi has equal src and dst
        code.forEach { (block, blockCmds) ->
            if (blockCmds.dropLast(1).any { it is TACCmd.Simple.JumpiCmd || it is TACCmd.Simple.JumpCmd }) {
                throw TACStructureException("Got jumps in the middle of a block $block: $blockCmds")
            }
            val last = blockCmds.last()
            if (last is TACCmd.Simple.JumpiCmd && last.dst == last.elseDst) {
                throw TACStructureException("Got a conditional jump which is not conditional in $block: $last")
            }
        }

        // enforcement of this [TACProgram] by all the snippet commands.
        val snippetEnforceScopeResults: List<EnforceScopeResult> = mutableListOf<EnforceScopeResult>().also {
            ltacStream().mapNotNull {
                it.ptr `to?` (it.cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.v as? ScopeSnippet
            }.forEach { (ptr, scopingSnippet) ->
                it.add(scopingSnippet.enforceScope(ptr, analysisCache.graph))
            }
        }
        snippetEnforceScopeResults.joinToExceptionOrNull(::TACStructureException)?.let { throw it }
    }

    /*
     * Do not try to serialize the analysis cache (it would be *truly* disastrous), if we deserialize a tac program,
     * start the cache from scratch
     */
    @Transient
    private var _analysisCache: AnalysisCache? = null

    sealed class DelegateContext : Serializable {
        abstract fun mapStorage(v: TACSymbol.Var): TACSymbol.Var

        object None : DelegateContext() {
            fun readResolve(): Any = None
            override fun mapStorage(v: TACSymbol.Var): TACSymbol.Var = v
        }

        data class DelegateFrom(val n: BigInteger) : DelegateContext() {
            override fun mapStorage(v: TACSymbol.Var): TACSymbol.Var {
                check(v.meta.find(TACMeta.SCALARIZATION_SORT) == ScalarizationSort.UnscalarizedBuffer)
                check(v.meta.find(STORAGE_KEY) != null)
                return getStorageInternal(this.n).withMeta {
                    it + (TACMeta.SCALARIZATION_SORT to ScalarizationSort.UnscalarizedBuffer)
                }
            }
        }
    }

    inner class AnalysisCache : IAnalysisCache {
        override val graph: TACCommandGraph by lazy {
            TACCommandGraph(
                this@CoreTACProgram.blockgraph,
                this@CoreTACProgram.code,
                this@CoreTACProgram.symbolTable,
                this,
                name = this@CoreTACProgram.name
            )
        }

        override val def: IDefAnalysis by lazy { LooseDefAnalysis.createForCache(graph) }

        override val strictDef: StrictDefAnalysis by lazy { StrictDefAnalysis.createForCache(graph) }

        override val use: IUseAnalysis by lazy { IUseAnalysis.UseAnalysis(graph) }

        override val lva: LiveVariableAnalysis by lazy { LiveVariableAnalysis(graph) }

        override val revertBlocks: Set<NBId> by lazy { RevertBlockAnalysis.findRevertBlocks(this.graph) }

        override val domination by lazy { SimpleDominanceAnalysis(blockgraph) }

        override val variableLookup: Map<NBId, Set<TACSymbol.Var>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            VariableLookupComputation.compute(this@CoreTACProgram.ltacStream())
        }

        override val gvn: IGlobalValueNumbering by lazy { GlobalValueNumbering(graph) }

        override val naturalBlockScheduler: NaturalBlockScheduler by lazy {
            NaturalBlockScheduler.createForCache(graph)
        }
        override val reachability by lazy {
            transitiveClosure(graph.blockSucc, reflexive = true)
        }

        init {
            // optionally record some stats
            if (Config.dumpCacheStatistics.get()) {
                val key = name.toSDFeatureKey()
                SDCollectorFactory.collector().collectFeature(GeneralKeyValueStats<Int>(key).addKeyValue("blocks", code.size))
                SDCollectorFactory.collector().collectFeature(GeneralKeyValueStats<Int>(key).addKeyValue("commands", code.values.sumOf { it.size }))
            }
        }
    }

    override val analysisCache: IAnalysisCache
        get() {
            if (_analysisCache == null) {
                _analysisCache = AnalysisCache()
            }
            return _analysisCache!!
        }

    fun copyWithPatchingAndTags(copy: PatchingTACProgram<TACCmd.Simple>, tags: Tags<TACSymbol.Var>): CoreTACProgram {
        val (code, block) = copy.toCode(TACCmd.Simple.NopCmd)
        return copy(
            code = code, blockgraph = block,
            symbolTable = symbolTable.copy(tags = symbolTable.tags.overwriteTags(tags)),
            check = true
        )
    }

    override fun getNodeCode(n: NBId): List<TACCmd.Simple> {
        return code.let { g ->
            if (n in g) {
                g[n]!!
            } else {
                throw Exception("Node $n does not appear in graph")
            }
        }
    }

    override fun toPatchingProgram(): SimplePatchingProgram =
        SimplePatchingProgram(code, blockgraph, procedures.toMutableSet(), name, entryBlockIdInternal)

    fun toPatchingProgram(name : String): SimplePatchingProgram =
        SimplePatchingProgram(code, blockgraph, procedures.toMutableSet(), name, entryBlockIdInternal)

    fun patching(function: CoreTACProgram.(SimplePatchingProgram) -> Unit): CoreTACProgram {
        val p = toPatchingProgram()
        this.function(p)
        return p.toCode(this)
    }

    fun simpleSummaries(): CoreTACProgram {
        val sumSucc =
            parallelLtacStream().mapNotNull { it.maybeNarrow<TACCmd.Simple.SummaryCmd>() }
                .map { (ptr, cmd) ->
                    val blockSucc = blockgraph[ptr.block].orEmpty()
                    val succ =
                        (cmd.summ as? ConditionalBlockSummary)?.skipTarget?.let { blockSucc - it }
                    ptr to succ
                }.collect(Collectors.toList())
        return if (sumSucc.isEmpty()) {
            // skip patching if there isn't any [SummaryCmd]
            this
        } else {
            patching {
                for ((ptr, succ) in sumSucc) {
                    it.replaceCommand(ptr, listOf(TACCmd.Simple.NopCmd), succ)
                }
            }
        }
    }

    fun parallelLtacStream(): Stream<LTACCmd> = code.entries.parallelStream().flatMap { (block, cmds) ->
        cmds.mapIndexed { pos, cmd ->
            LTACCmd(
                cmd = cmd,
                ptr = CmdPointer(block = block, pos = pos)
            )
        }.stream()
    }

    fun ltacStream(): Stream<LTACCmd> = parallelLtacStream().sequential()

    /**
     * Retrieves views of [SummaryStack.SummaryStart] annotations, in a topological
     * order with respect to this [CoreTACProgram].
     * Notice: such annotations exist only after the summaries (kept in [CallSummary]s) get inlined,
     * so calling this function may be useful only after the summaries inlining.
     */
    fun topoSortedSummaryStart(): List<LTACCmdView<TACCmd.Simple.AnnotationCmd>> {
        val graph = analysisCache.graph
        return topoSortFw.flatMap { block ->
            graph.elab(block).commands.mapNotNull {
                if (it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.v is SummaryStack.SummaryStart) {
                    it.narrow()
                } else {
                    null
                }
            }
        }
    }

    companion object {
        data class CopyMapperState(
            val targetCallIndex: CallId,
            val tags: Tags.Builder<TACSymbol.Var>,
            val idMap: MutableMap<Pair<Any, Int>, Int>,
            val remapCallSummary: Boolean
        )


        private fun isVarWithoutCallIndex(v: TACSymbol.Var) =
            STORAGE_KEY in v.meta ||
            ORIGINAL_STORAGE_KEY in v.meta ||
            TRANSIENT_STORAGE_KEY in v.meta ||
            ORIGINAL_TRANSIENT_STORAGE_KEY in v.meta ||
            CONTRACT_ADDR_KEY in v.meta ||
            NO_CALLINDEX in v.meta ||
            IS_IMMUTABLE in v.meta ||
            TACMeta.STORAGE_READ_TRACKER in v.meta ||
            TACMeta.SUMMARY_GLOBAL in v.meta ||
            v.meta[TACSymbol.Var.KEYWORD_ENTRY]?.name == CVLKeywords.lastReverted.keyword ||
            v.meta[TACSymbol.Var.KEYWORD_ENTRY]?.name == CVLKeywords.lastHasThrown.keyword


        val copyRemapper = CodeRemapper(
            variableMapper = { state, v ->
                v.also {
                    state.tags.set(it, it.tag)
                }
            },
            callIndexStrategy = object : CodeRemapper.CallIndexStrategy<CopyMapperState> {
                override fun remapCallIndex(
                    state: CopyMapperState,
                    callIndex: CallId,
                    computeFreshId: (CallId) -> CallId
                ): CallId {
                    return if(callIndex == NBId.ROOT_CALL_ID) {
                        state.targetCallIndex
                    } else {
                        computeFreshId(callIndex)
                    }
                }

                override fun remapVariableCallIndex(
                    state: CopyMapperState,
                    v: TACSymbol.Var,
                    computeFreshId: (CallId) -> CallId
                ): TACSymbol.Var {
                    return if(isVarWithoutCallIndex(v)) {
                        v
                    } else {
                        super.remapVariableCallIndex(state, v, computeFreshId)
                    }
                }
            },
            blockRemapper = { bId, remap, _, _ ->
                bId.copy(calleeIdx = remap(bId.calleeIdx))
            },
            idRemapper = CodeRemapper.IdRemapperGenerator { state ->
                { flg, curr, gen ->
                    if (!state.remapCallSummary && flg == Allocator.Id.CALL_SUMMARIES) {
                        curr
                    } else {
                        state.idMap.getOrPut(flg to curr, gen)
                    }
                }
            }
        )

        operator fun invoke(
            code: BlockNodes<TACCmd.Simple>,
            blockgraph: BlockGraph,
            name: String,
            symbolTable: TACSymbolTable,
            instrumentationTAC: InstrumentationTAC,
            procedures: Set<Procedure>,
            check: Boolean = true, // TODO: REMOVE THIS AFTER REMOVING IRTOTACCONVERTER
            entryBlock: NBId? = null,
        ) = CoreTACProgram(
            code,
            blockgraph,
            name,
            symbolTable,
            instrumentationTAC,
            procedures,
            check,
            entryBlock,
        ).let { codeToCheck ->
            if (check) {
                try {
                    codeToCheck.checkCodeStructure()
                    TACTypeChecker.checkProgram(codeToCheck)
                        .also { it.checkCodeStructure() } // this will be a sanity check for now that TypeChecker.checkProgram doesn't screw up structure
                } catch (e: Exception) {
                    when (e) {
                        is TACTypeCheckerException, is TACStructureException -> {
                            try {
                                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                                    codeToCheck,
                                    ReportTypes.ERROR,
                                    StaticArtifactLocation.Reports,
                                    DumpTime.AGNOSTIC
                                )
                            } catch (@Suppress("TooGenericExceptionCaught") eDump: Exception) {
                                logger.warn(eDump) { "Failed to dump error file for ${codeToCheck.name}" }
                            }
                        }
                    }
                    throw e
                }
            } else {
                codeToCheck
            }
        }

        operator fun invoke(
            code: BlockNodes<TACCmd.Simple>,
            blockgraph: BlockGraph,
            name: String,
            symbolTable: TACSymbolTable,
            ufAxioms: UfAxioms,
            procedures: Set<Procedure>,
            check: Boolean = true,
            entryBlock: NBId? = null
        ) = invoke(code, blockgraph, name, symbolTable, InstrumentationTAC(ufAxioms), procedures, check, entryBlock)

        operator fun invoke(
            code: BlockNodes<TACCmd.Simple>,
            blockgraph: BlockGraph,
            name: String,
            symbolTable: TACSymbolTable,
            procedures: Set<Procedure>,
            check: Boolean = true,
            entryBlock: NBId? = null
        ) = invoke(code, blockgraph, name, symbolTable, InstrumentationTAC(UfAxioms.empty()), procedures, check, entryBlock)

        fun readBinary(where: String): CoreTACProgram {
            try {
                val tacWithAllocator = ObjectSerialization.readObject<TACWithAllocator>(where)
                Allocator.restore(tacWithAllocator.memento)
                return tacWithAllocator.tac
            } catch (e: Exception) {
                logger.error(
                    "Failed to deserialize binary dump of TAC object at $where"
                )
                throw e
            }
        }

        fun readBinaryStream(inputStream: InputStream): CoreTACProgram {
            try {
                val tacWithAllocator = ObjectSerialization.readObject<TACWithAllocator>(inputStream)
                Allocator.restore(tacWithAllocator.memento)
                return tacWithAllocator.tac
            } catch (e: Exception) {
                logger.error(
                    e, "Failed to deserialize binary dump of TAC object"
                )
                throw e
            }
        }

        fun <T> parseStream(
            inputStream: InputStream, fileName: String?,constructor: (
                nodes: BlockNodes<TACCmd.Simple>,
                blockGraph: BlockGraph,
                symbolTable: TACSymbolTable,
                axioms: UfAxioms
            ) -> T
        ): T {
            return DeserializeTacStream(inputStream, fileName, constructor)
        }

        fun fromStream(stream: InputStream, nm: String): CoreTACProgram {
            return parseStream(stream, nm) { nodes, blockgraph, typescope, axioms ->
                CoreTACProgram(
                    nodes,
                    blockgraph,
                    nm,
                    typescope,
                    axioms,
                    IProcedural.empty()
                )
            }
        }

        fun empty(name: String) = CoreTACProgram(
            mapOf(),
            MutableBlockGraph(),
            name,
            TACSymbolTable.empty(),
            UfAxioms.empty(),
            IProcedural.empty()
        )


        /**
         * Collects and returns all variable symbols in the given [_code] that are not memory scalar variables
         */
        fun symbolsFromCode(_code: BlockNodes<TACCmd.Simple>): Set<TACSymbol.Var> {
            val memScalarVars = setOf(
                TACKeyword.MEM0.getName(),
                TACKeyword.MEM32.getName(),
                TACKeyword.MEM64.getName()
            )
            return _code.values.flatMap { cmds ->
                cmds.flatMap { cmd ->
                    val rhs = cmd.getRhs()

                    logger.debug { "Checking rhs $rhs" }
                    rhs
                        .filter {
                            it is TACSymbol.Var
                                    && it.namePrefix !in memScalarVars
                        }
                        .map { it as TACSymbol.Var }

                }
            }.toSet()
        }
    }

    fun createCopyWithRemapper(
        id: CallId,
        callerDefiningContract: BigInteger = BigInteger.ZERO,
        remapCallSummary: Boolean = true
    ) : Pair<CoreTACProgram, (Allocator.Id, Int) -> Int> {
        val idMap = mutableMapOf<Pair<Any, Int>, Int>()
        val state = CopyMapperState(
            tags = tagsBuilder(),
            idMap = idMap,
            targetCallIndex = id,
            remapCallSummary = remapCallSummary
        )
        // TODO: Use TransientVarsRenamer instead, need to update it with Jump handling from here

        loggerSlave.info { "Toposort: $topoSortFw - for $name" }
        loggerSlave.info { "Blockgraph: $blockgraph" }
        loggerSlave.info { "Code: $code" }


        loggerSlave.info { "Increasing copies by $id of $name for caller $callerDefiningContract" }
        val (newBlocks, newGraph, procedures) = remap(copyRemapper, state)

        return invoke(
            newBlocks,
            newGraph,
            name,
            symbolTable.copy(tags = state.tags.build()),
            instrumentationTAC,
            procedures
        ).prependToBlock0(listOf(TACCmd.Simple.LabelCmd("Start procedure $name")))
            .let {
                val patch = it.toPatchingProgram()
                for ((ptr) in it.analysisCache.graph.sinks) {
                    patch.replace(ptr) { c ->
                        listOf(TACCmd.Simple.LabelCmd("End procedure $name"), c)
                    }
                }
                patch.toCode(it)
            } to { idSequence: Allocator.Id, i: Int ->
                idMap[idSequence to i] ?: i
            }

    }


    fun createCopy(
        id: CallId,
        callerDefiningContract: BigInteger = BigInteger.ZERO,
        remapCallSummary: Boolean = true
    ): CoreTACProgram { // Note that this is good only for codes with a single call already
        return createCopyWithRemapper(
            id, callerDefiningContract, remapCallSummary
        ).first
    }

    fun <S> remap(
        codeRemapper: CodeRemapper<S>,
        state: S
    ): Triple<BlockNodes<TACCmd.Simple>, BlockGraph, Set<Procedure>> {
        val mapper = codeRemapper.commandMapper(state)

        val newBlocks = code.entries.associate { e ->
            codeRemapper.remapBlockId(
                state,
                CodeRemapper.BlockRemappingStrategy.RemappingContext.BLOCK_ID,
                e.key
            ) to e.value.map(mapper)
        }
        val newGraph = blockgraph.entries.associateTo(MutableBlockGraph()) { e ->
            codeRemapper.remapBlockId(
                state,
                CodeRemapper.BlockRemappingStrategy.RemappingContext.BLOCK_ID,
                e.key
            ) to e.value.updateElements {
                codeRemapper.remapBlockId(
                    state,
                    CodeRemapper.BlockRemappingStrategy.RemappingContext.SUCCESSOR,
                    it
                )
            }
        }
        val procedures = procedures.mapToSet { codeRemapper.remapProcedure(state, it) }

        return Triple(newBlocks, newGraph, procedures)
    }


    fun removeNodesNotReachableFromStart(): CoreTACProgram {
        val reachableFromRoot = object : VisitingWorklistIteration<NBId, NBId, List<NBId>>() {
            override fun reduce(results: List<NBId>): List<NBId> =
                results

            override fun process(it: NBId): StepResult<NBId, NBId, List<NBId>> {
                check(it in blockgraph) {
                    "Did not expect $it to not have an entry in the blockgraph. Taking an empty list"
                }
                return StepResult.Ok(blockgraph[it] ?: emptyList(), listOf(it))
            }
        }.submit(listOf(StartBlock))

        val blockstoRemove = blockgraph.keys.minus(reachableFromRoot)
        logger.debug { "Removing $blockstoRemove" }
        val newCode = code.filter { b -> !blockstoRemove.contains(b.key) }
        val newBlockGraph = blockgraph.filterTo(MutableBlockGraph()) { b -> !blockstoRemove.contains(b.key) }
        return copy(code = newCode, blockgraph = newBlockGraph)
    }

    override fun <U: TACCmd.Simple> prependToBlock0(l: List<U>): CoreTACProgram {
        val startBlock = getStartingBlock()
        return addToBlock(l, startBlock, true)
    }

    override fun <U: TACCmd.Simple> prependToBlock0(c: CommandWithRequiredDecls<U>): CoreTACProgram =
        addToBlock(c.cmds, getStartingBlock(), true, c.varDecls)

    private fun addToBlock(
            l: List<TACCmd.Simple>,
            b: NBId,
            isBefore: Boolean,
            varDecls: Set<TACSymbol.Var> = setOf(),
    ): CoreTACProgram = _addToBlock(l, b, isBefore, varDecls) {
        blockIdentifier: NBId ->
        val tacBlock = analysisCache.graph.elab(blockIdentifier)
        GenericTACBlock(tacBlock.id, tacBlock.commands)
    } as CoreTACProgram

    /**
     * Adds a sink with the new sink node guaranteed to have the callId of 0 (i.e. not call specific)
     * @param cmds - a [CommandwithRequiredDecls]
     */
    fun addSinkMainCall(cmds: CommandWithRequiredDecls<TACCmd.Simple>): Pair<CoreTACProgram, NBId> {
        val newBlockId = Allocator.getNBId()
        val newCode = code.plus(Pair(newBlockId, cmds.cmds))
        val newDecls = Tags(cmds.varDecls)
        val newGraph = MutableBlockGraph(blockgraph)
        getEndingBlocks().forEach {
            newGraph.put(it, treapSetOf(newBlockId))
        }
        newGraph.put(newBlockId, treapSetOf())

        return this.copy(
            code = newCode,
            blockgraph = newGraph,
            symbolTable = this.symbolTable.mergeDecls(newDecls)
        ) to newBlockId
    }

    /**
     * Adds a sink with the new sink node guaranteed to have the callId of 0 (i.e. not call specific)
     * @param cmds - a list of [TACCmd.Simple], without declarations
     */
    fun addSinkMainCall(cmds: List<TACCmd.Simple>): Pair<CoreTACProgram, NBId> {
        val newBlockId = Allocator.getNBId()
        val newCode = code.plus(Pair(newBlockId, cmds))
        val newGraph = MutableBlockGraph(blockgraph)
        getEndingBlocks().forEach {
            newGraph.put(it, treapSetOf(newBlockId))
        }
        newGraph.put(newBlockId, treapSetOf())

        return this.copy(code = newCode, blockgraph = newGraph) to newBlockId
    }


    // TODO: If we move all legacy stuff to patching this can actually be after the returns as the function was named originally: addAfterReturns
    fun insertBeforeLastCommandOfSinks(cmds: CommandWithRequiredDecls<TACCmd.Simple>): CoreTACProgram =
        _insertBeforeLastCommandOfSinks(cmds, analysisCache.graph.sinks) as CoreTACProgram

    override fun <U: TACCmd.Simple> appendToSinks(cmds: CommandWithRequiredDecls<U>): CoreTACProgram =
            _appendToSinks(cmds, analysisCache.graph.sinks) as CoreTACProgram


    fun getSymbols(): Set<TACSymbol.Var> {
        return symbolsFromCode(this.code)
    }

    fun hasExternalCalls(): Boolean {
        return code.any { bWithCode ->
            bWithCode.value.any { cmd ->
                cmd is TACCmd.Simple.CallCore
            }
        }
    }

    fun externalCallsSources(): List<String> {
        return code.flatMap { bWithCode ->
            bWithCode.value.mapNotNull { cmd ->
                if (cmd is TACCmd.Simple.CallCore) {
                    cmd.metaSrcInfo?.getSourceCode() ?: bWithCode.key.toString()
                } else {
                    null
                }
            }
        }
    }

    fun addNotThrownInTheBeginning(): CoreTACProgram {
        return prependToBlock0(
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        CVLKeywords.lastHasThrown.toVar(),
                        TACSymbol.Const(BigInteger.ZERO, Tag.Bool)
                    )
                ),
                CVLKeywords.lastHasThrown.toVar()
            )
        )
    }

    fun prependCodeToCodeBlock(entry: NBId, codeToPrepend: CommandWithRequiredDecls<TACCmd.Simple>): CoreTACProgram {
        return addToBlock(
            codeToPrepend.cmds,
            entry,
            true,
            varDecls = codeToPrepend.varDecls,
        )
    }


    fun hasInternalCalls(): Boolean {
        return ltacStream().anyMatch {
            (it.cmd as? TACCmd.Simple.AnnotationCmd)?.check(INTERNAL_FUNC_START) {
                true
            } ?: false
        }
    }

    fun asCVLTACProgram(): CVLTACProgram = CVLTACProgram(code, blockgraph, name, symbolTable, procedures) { this.ufAxioms }

    val internalFuncExitVars: Set<TACSymbol.Var> by lazy {
        this.parallelLtacStream()
            .mapNotNull { it.maybeNarrow<TACCmd.Simple.AnnotationCmd>() }
            .filter { it.cmd.annot.v is InternalFuncExitAnnotation }
            .flatMap { it.cmd.mentionedVariables.stream() }
            .collect(Collectors.toSet())
    }

    fun getHavocedSymbols(): Set<TACSymbol.Var> = this.parallelLtacStream()
        .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignHavocCmd>()?.cmd?.lhs }
        .collect(Collectors.toSet())

    fun convertToLoopFreeCode(): CoreTACProgram {
        loggerTimes.info { "Handling loops" }
        val loopHandlingStart = System.currentTimeMillis()
        val bmcRunner = BMCRunner(
             Config.LoopUnrollConstant.get(),
            this
        ) // unroll once for quantification mode
        val unrolledCode = bmcRunner.bmcUnroll()
        val loopHandlingEnd = System.currentTimeMillis()
        loggerTimes.info { "Handled loops in ${(loopHandlingEnd - loopHandlingStart) / 1000}s" }

        unrolledCode.logStats("Unrolled", logger)
        return unrolledCode
    }
}

internal fun BlockGraph.leafNodes() =
    mapNotNull { (node, children) ->
        if (children.isEmpty()) {
            node
        } else {
            null
        }
    }

/**
 * if `this` is a reachablility map, this can be used to check if [from] can reach [to]. This is reflexive
 */
fun MultiMap<NBId, NBId>.canReach(from: CmdPointer, to: CmdPointer) =
    if (from.block == to.block) {
        from.pos <= to.pos
    } else {
        to.block in this[from.block].orEmpty()
    }
